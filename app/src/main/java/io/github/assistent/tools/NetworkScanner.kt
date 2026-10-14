package io.github.assistent.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import io.github.assistent.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** A device found on the network, with everything needed to wake, recognise and control it. */
@Serializable
data class NetDevice(
    val host: String,
    val name: String = "",
    /** tv, pc, cast, speaker, phone, router, printer, other */
    val kind: String = "other",
    val mac: String = "",
    val ports: List<Int> = emptyList(),
    val vendor: String = "",
    val model: String = "",
    /** UPnP DIAL application URL (YouTube/Netflix launch on smart TVs). */
    val dialUrl: String = "",
    val sources: List<String> = emptyList(),
)

/**
 * Finds what is on the home network without any app on the other side:
 * - mDNS/SSDP announcements (Android TV, Chromecast, smart TVs, speakers) — via [DeviceDiscovery];
 * - a sweep of the whole /24 for the ports that identify devices (ADB 5555, Cast 8008/8009, Samsung 8001/8002,
 *   LG webOS 3000/3001, Windows 135/139/445/3389, SSH 22, AirPlay 7000, Android TV remote 6466);
 * - names and MACs: NetBIOS node status for Windows PCs (name + MAC), UPnP device descriptions (friendly name,
 *   maker, model, DIAL URL). Android TVs get their MAC over ADB once authorised.
 * The MAC is what Wake-on-LAN needs to turn a device on later, when it is off.
 */
class NetworkScanner(private val context: Context, private val http: OkHttpClient) {
    private val _progress = MutableStateFlow<String>("")
    val progress: StateFlow<String> = _progress

    suspend fun scan(): List<NetDevice> = withContext(Dispatchers.IO) {
        // Android filters multicast/broadcast answers unless a multicast lock is held.
        val lock = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            ?.createMulticastLock("assistent-scan")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        try { scanLocked() } finally { runCatching { lock?.release() } }
    }

    private suspend fun scanLocked(): List<NetDevice> = withContext(Dispatchers.IO) {
        val (myIp, prefix) = localAddress() ?: return@withContext emptyList<NetDevice>().also { _progress.value = "Sem Wi-Fi/rede local" }
        val t0 = System.currentTimeMillis()
        _progress.value = "Varrendo a rede ${myIp.substringBeforeLast('.')}.0/$prefix…"
        val hosts = hostsOf(myIp, prefix).filter { it != myIp }
        val sem = Semaphore(64)
        var done = 0
        // Announcements, UPnP descriptions and the port sweep all at once: the scan takes as long as the slowest.
        val (announced, upnp, swept) = coroutineScope {
            val a = async { runCatching { DeviceDiscovery.scan(context, 3_000) }.getOrDefault(emptyList()) }
            val u = async { runCatching { ssdpDescriptions() }.getOrDefault(emptyMap()) }
            val sw = hosts.map { h ->
                async {
                    sem.withPermit {
                        val open = coroutineScope { PORTS.map { p -> async(PROBES) { p.takeIf { isOpen(h, it, 350) } } }.awaitAll().filterNotNull() }
                        synchronized(this@NetworkScanner) { done++; if (done % 16 == 0) _progress.value = "Varrendo a rede: $done/${hosts.size}" }
                        if (open.isEmpty()) null else h to open
                    }
                }
            }
            Triple(a.await(), u.await(), sw.awaitAll().filterNotNull().toMap())
        }
        val all = (swept.keys + announced.map { it.host } + upnp.keys).toSet()
        _progress.value = "Identificando ${all.size} aparelhos…"
        val devices = coroutineScope {
            all.map { h ->
                async {
                    val ports = swept[h].orEmpty()
                    val nb = if (ports.any { it in setOf(135, 139, 445, 3389) }) runCatching { netbios(h) }.getOrNull() else null
                    val u = upnp[h]
                    val ann = announced.filter { it.host == h }
                    val name = listOfNotNull(u?.name, ann.firstOrNull()?.name?.takeIf { !it.matches(Regex("[0-9.]+")) }, nb?.first)
                        .firstOrNull { it.isNotBlank() } ?: h
                    NetDevice(
                        host = h, name = name, kind = classify(ports, ann.map { it.source }, u),
                        mac = nb?.second.orEmpty(), ports = ports, vendor = u?.maker.orEmpty(), model = u?.model.orEmpty(),
                        dialUrl = u?.dialUrl.orEmpty(),
                        sources = (ann.map { it.source } + listOfNotNull(u?.let { "upnp" }, nb?.let { "netbios" }, if (ports.isNotEmpty()) "portas" else null)).distinct(),
                    )
                }
            }.awaitAll()
        }.sortedWith(compareBy({ KIND_ORDER.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }, { it.name }))
        _progress.value = "${devices.size} aparelhos encontrados"
        devices
    }

    /** IPv4 of this phone on Wi-Fi/Ethernet and its prefix length. */
    private fun localAddress(): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val la: LinkAddress = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return la.address.hostAddress!! to la.prefixLength
    }

    /** Broadcast address of the local network (directed broadcast works better than 255.255.255.255 on many routers). */
    fun broadcastAddress(): String? {
        val (ip, prefix) = localAddress() ?: return null
        return directedBroadcast(ip, prefix)
    }

    private data class Upnp(val name: String, val maker: String, val model: String, val dialUrl: String)

    /** SSDP search, then each device's description XML (friendly name, manufacturer, model, DIAL Application-URL). */
    private suspend fun ssdpDescriptions(): Map<String, Upnp> = coroutineScope {
        val locations = linkedMapOf<String, String>()
        DatagramSocket().use { s ->
            s.soTimeout = 300
            for (st in listOf("ssdp:all", "urn:dial-multiscreen-org:service:dial:1")) {
                val req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                s.send(DatagramPacket(req, req.size, InetAddress.getByName("239.255.255.250"), 1900))
            }
            val until = System.currentTimeMillis() + 2_500
            while (System.currentTimeMillis() < until) {
                val buf = ByteArray(4096)
                val pkt = DatagramPacket(buf, buf.size)
                runCatching { s.receive(pkt) }.onSuccess {
                    val text = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
                    val loc = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: return@onSuccess
                    val host = pkt.address.hostAddress ?: return@onSuccess
                    // Prefer the DIAL description (it carries the Application-URL header).
                    if (text.contains("dial-multiscreen", true) || host !in locations) locations[host] = loc
                }
            }
        }
        locations.map { (host, loc) ->
            async {
                runCatching {
                    http.newCall(Request.Builder().url(loc).build()).execute().use { r ->
                        val xml = r.body?.string().orEmpty()
                        host to Upnp(
                            tag(xml, "friendlyName"), tag(xml, "manufacturer"), tag(xml, "modelName"),
                            r.header("Application-URL").orEmpty(),
                        )
                    }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private fun reverseName(h: String): String? = runCatching {
        InetAddress.getByName(h).canonicalHostName.takeIf { it != h && !it.matches(Regex("[0-9.]+")) }?.substringBefore('.')
    }.getOrNull()

    companion object {
        /** Socket connects block a thread each: give the sweep its own wide pool (the default IO pool is 64). */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val PROBES = Dispatchers.IO.limitedParallelism(600)

        val PORTS = listOf(5555, 8009, 8008, 8001, 8002, 3000, 3001, 6466, 7000, 135, 139, 445, 3389, 22, 9100, 631, 80)
        private val KIND_ORDER = listOf("tv", "cast", "pc", "speaker", "phone", "printer", "router", "other")

        fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        }.getOrDefault(false)

        fun classify(ports: List<Int>, sources: List<String>, u: Any?): String {
            val text = (sources.joinToString(" ") + " " + (u?.toString() ?: "")).lowercase()
            return when {
                5555 in ports || 6466 in ports || "androidtv" in text || "_adb" in text -> "tv"
                8001 in ports || 8002 in ports || 3000 in ports || 3001 in ports || "tv" in text && "upnp" in text || "mediarenderer" in text -> "tv"
                8009 in ports || 8008 in ports || "googlecast" in text -> "cast"
                3389 in ports || 445 in ports || 139 in ports || 135 in ports -> "pc"
                7000 in ports || "airplay" in text || "speaker" in text -> "speaker"
                9100 in ports || 631 in ports -> "printer"
                ports == listOf(80) || ports.isEmpty() -> "other"
                22 in ports -> "pc"
                else -> "other"
            }
        }

        fun hostsOf(ip: String, prefix: Int): List<String> {
            // Sweep the /24 the phone is on (bigger networks: only its own /24, to stay quick).
            val base = ip.substringBeforeLast('.')
            return (1..254).map { "$base.$it" }
        }

        fun directedBroadcast(ip: String, prefix: Int): String {
            val a = ip.split('.').map { it.toInt() }
            val v = (a[0] shl 24) or (a[1] shl 16) or (a[2] shl 8) or a[3]
            val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix))
            val b = v or mask.inv()
            return listOf(b ushr 24 and 255, b ushr 16 and 255, b ushr 8 and 255, b and 255).joinToString(".")
        }

        private fun tag(xml: String, name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

        /** NetBIOS node-status request ("*" name): Windows answers with its computer name and MAC address. */
        fun netbiosRequest(id: Int = 0x4242): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.write(0x20)
            // "*" padded with zeros to 16 bytes, first-level encoded: each nibble + 'A'.
            val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
            raw.forEach { b -> out.write(((b.toInt() shr 4) and 0xF) + 'A'.code); out.write((b.toInt() and 0xF) + 'A'.code) }
            out.write(0)
            out.write(byteArrayOf(0, 0x21, 0, 1)) // NBSTAT, IN
            return out.toByteArray()
        }

        /** Parses a node-status answer → (computer name, MAC "aa:bb:…"). */
        fun parseNetbios(resp: ByteArray): Pair<String, String>? {
            if (resp.size < 57) return null
            var p = 12
            while (p < resp.size && resp[p].toInt() != 0) p += (resp[p].toInt() and 0xFF) + 1
            p += 1 + 4 + 4 + 2 // name end, type/class, TTL, rdlength
            if (p >= resp.size) return null
            val count = resp[p].toInt() and 0xFF
            p++
            var name = ""
            for (i in 0 until count) {
                val n = String(resp, p, 15, Charsets.US_ASCII).trim()
                val suffix = resp[p + 15].toInt() and 0xFF
                val flags = ((resp[p + 16].toInt() and 0xFF) shl 8) or (resp[p + 17].toInt() and 0xFF)
                if (name.isEmpty() && suffix == 0x00 && flags and 0x8000 == 0) name = n // unique workstation name
                p += 18
            }
            if (p + 6 > resp.size) return null
            val mac = (0 until 6).joinToString(":") { "%02x".format(resp[p + it]) }
            return name to mac.takeIf { it != "00:00:00:00:00:00" }.orEmpty()
        }

        fun netbios(host: String): Pair<String, String>? = DatagramSocket().use { s ->
            s.soTimeout = 800
            val req = netbiosRequest()
            s.send(DatagramPacket(req, req.size, InetAddress.getByName(host), 137))
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            parseNetbios(buf.copyOf(pkt.length))
        }
    }
}
