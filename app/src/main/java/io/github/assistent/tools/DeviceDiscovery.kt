package io.github.assistent.tools

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI

@Serializable
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val kind: String,
    val mac: String = "",
    val source: String,
)

/** Wi-Fi discovery for Android TV/ADB/Cast and ordinary UPnP media renderers. */
internal object DeviceDiscovery {
    private val SERVICE_TYPES = listOf(
        "_adb-tls-connect._tcp.",
        "_adb._tcp.",
        "_androidtvremote2._tcp.",
        "_googlecast._tcp.",
        "_airplay._tcp.",
    )

    suspend fun scan(context: Context, timeoutMs: Long = 2_500): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val nsd = async { scanNsd(context, timeoutMs) }
        val ssdp = async { scanSsdp(timeoutMs) }
        (nsd.await() + ssdp.await())
            .map { it.copy(mac = it.mac.ifBlank { macFor(it.host) }) }
            .distinctBy { "${it.host}:${it.port}" }
    }

    private suspend fun scanNsd(context: Context, timeoutMs: Long): List<DiscoveredDevice> {
        val manager = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        return coroutineScope {
            SERVICE_TYPES.map { type -> async { scanNsdType(manager, type, timeoutMs) } }.awaitAll().flatten()
        }
    }

    private suspend fun scanNsdType(manager: NsdManager, type: String, timeoutMs: Long): List<DiscoveredDevice> {
            val services = withTimeoutOrNull(timeoutMs) {
                callbackFlow {
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) = Unit
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                                override fun onServiceResolved(info: NsdServiceInfo) { trySend(info) }
                            })
                        }
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                        override fun onDiscoveryStopped(serviceType: String) = Unit
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    }
                    manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
                }.toList()
            } ?: emptyList()
            return services.mapNotNull { service ->
                val host = service.host?.hostAddress ?: return@mapNotNull null
                val kind = when {
                    type.startsWith("_adb") -> "tv"
                    type.startsWith("_googlecast") -> "cast"
                    else -> "media"
                }
                DiscoveredDevice(service.serviceName.ifBlank { host }, host, service.port, kind, source = "mdns:$type")
            }
    }

    private fun scanSsdp(timeoutMs: Long): List<DiscoveredDevice> {
        val request = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: ssdp:all\r\n\r\n"
        val found = linkedMapOf<String, DiscoveredDevice>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val bytes = request.toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 1900))
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                val buffer = ByteArray(8192)
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onSuccess {
                    val headers = parseHeaders(String(packet.data, 0, packet.length, Charsets.ISO_8859_1))
                    val location = headers["location"] ?: return@onSuccess
                    val uri = runCatching { URI(location) }.getOrNull() ?: return@onSuccess
                    val host = uri.host ?: return@onSuccess
                    val port = if (uri.port > 0) uri.port else 80
                    val text = "${headers["server"].orEmpty()} ${headers["st"].orEmpty()}".lowercase()
                    val kind = if ("mediarenderer" in text || "tv" in text || "upnp" in text) "tv" else "media"
                    found["$host:$port"] = DiscoveredDevice(headers["usn"]?.substringAfter("::") ?: host, host, port, kind, source = "ssdp")
                }
            }
        }
        return found.values.toList()
    }

    private fun parseHeaders(response: String): Map<String, String> = response.lineSequence()
        .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).lowercase() to line.substring(it + 1).trim() } }
        .toMap()

    private fun macFor(host: String): String = runCatching {
        java.io.File("/proc/net/arp").readLines().drop(1)
            .firstOrNull { it.trim().split(Regex("\\s+")).firstOrNull() == host }
            ?.trim()?.split(Regex("\\s+"))?.getOrNull(3).orEmpty()
    }.getOrDefault("").takeIf { it.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) }.orEmpty()
}
