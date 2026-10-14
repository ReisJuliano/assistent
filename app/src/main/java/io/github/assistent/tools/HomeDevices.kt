package io.github.assistent.tools

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import io.github.assistent.config.ConfigStore
import io.github.assistent.config.LanDeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** A Bluetooth device nearby. */
@Serializable
data class BtDevice(val name: String, val address: String, val type: String, val bonded: Boolean, val rssi: Int = 0)

/**
 * The home's TVs and PCs: scan the network, add what was found (with MAC for Wake-on-LAN), wake devices and wait until
 * they really answer, authorise ADB on Android TVs (and learn their MAC from the TV itself), scan Bluetooth, launch
 * YouTube on non-Android smart TVs with DIAL.
 */
class HomeDevices(
    private val context: Context,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    val adb: AdbRemote,
) {
    val scanner = NetworkScanner(context, http)
    private val _found = MutableStateFlow<List<NetDevice>>(emptyList())
    val found: StateFlow<List<NetDevice>> = _found
    private val _bt = MutableStateFlow<List<BtDevice>>(emptyList())
    val bluetooth: StateFlow<List<BtDevice>> = _bt
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    suspend fun scan(): List<NetDevice> = scanner.scan().also { list ->
        // Remember the latest IP of devices already saved (DHCP may move them).
        config.update { c ->
            c.copy(lanDevices = c.lanDevices.map { d ->
                val match = list.firstOrNull { d.mac.isNotBlank() && it.mac.equals(d.mac, true) } ?: return@map d
                d.copy(host = match.host)
            })
        }
        _found.value = list
    }

    /** Saves a found device (or updates the saved one with the same host/MAC). */
    fun add(d: NetDevice, name: String = d.name, room: String = ""): LanDeviceConfig {
        val id = slug(name).ifBlank { "device_" + d.host.replace('.', '_') }
        val dev = LanDeviceConfig(
            id = id, name = name.ifBlank { d.host }, mac = d.mac, host = d.host,
            broadcast = scanner.broadcastAddress() ?: "255.255.255.255",
            kind = if (d.kind == "cast") "tv" else d.kind,
            checkPort = bestCheckPort(d.ports), adbPort = if (5555 in d.ports) 5555 else 5555,
            room = room, dialUrl = d.dialUrl, model = listOf(d.vendor, d.model).filter { it.isNotBlank() }.joinToString(" "),
        )
        config.update { c ->
            val others = c.lanDevices.filterNot { it.id == id || it.host == d.host || (d.mac.isNotBlank() && it.mac.equals(d.mac, true)) }
            c.copy(lanDevices = others + dev)
        }
        return dev
    }

    fun update(id: String, change: (LanDeviceConfig) -> LanDeviceConfig) =
        config.update { c -> c.copy(lanDevices = c.lanDevices.map { if (it.id == id) change(it) else it }) }

    fun remove(id: String) = config.update { c -> c.copy(lanDevices = c.lanDevices.filterNot { it.id == id }) }

    fun find(name: String): LanDeviceConfig? = config.current.lanDevices.let { all ->
        all.firstOrNull { it.id.equals(name, true) || it.name.equals(name, true) } ?: all.firstOrNull { it.name.contains(name, true) }
    }

    /** Is it answering right now (any of its known ports)? */
    fun online(d: LanDeviceConfig): Boolean {
        if (d.host.isBlank()) return false
        val ports = listOfNotNull(d.checkPort.takeIf { it > 0 }, if (d.kind == "tv") d.adbPort else null) +
            if (d.kind == "pc") listOf(445, 3389, 135) else listOf(8008, 8009, 8002, 3000)
        return ports.distinct().any { NetworkScanner.isOpen(d.host, it, 600) }
    }

    /** Wake-on-LAN, then wait until the device really answers (PCs take longer to boot). */
    suspend fun wake(d: LanDeviceConfig): String = withContext(Dispatchers.IO) {
        if (online(d)) return@withContext "${d.name} já está ligado"
        val mac = WakeOnLan.parseMac(d.mac) ?: return@withContext "FALHOU: ${d.name} não tem MAC cadastrado (necessário para ligar pela rede)"
        // Directed broadcast + global broadcast + last known IP, on ports 9 and 7, a few times (UDP can be lost).
        val targets = listOf(d.broadcast, scanner.broadcastAddress(), "255.255.255.255", d.host).filterNotNull().filter { it.isNotBlank() }.distinct()
        repeat(3) { for (t in targets) for (p in listOf(9, 7)) runCatching { WakeOnLan.send(mac, t, p) }; delay(300) }
        val limit = if (d.kind == "pc") 90 else 40
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < limit * 1000L) {
            delay(2_500)
            if (online(d)) return@withContext "${d.name} ligado (confirmado em ${(System.currentTimeMillis() - start) / 1000} s)"
        }
        "Wake-on-LAN enviado para ${d.name}, mas não respondeu em $limit s (sem confirmação; confira se o WoL está ativo na BIOS/rede da TV)"
    }

    /**
     * First ADB connection to an Android TV: the TV shows "Allow network debugging?". Once accepted, reads the model and
     * the network MAC from the TV (so it can be woken later even when it is off).
     */
    suspend fun authorizeAdb(d: LanDeviceConfig): String = try {
        val (model, mac) = adb.session(d.host, d.adbPort) { s ->
            val model = adb.shell(s, "getprop ro.product.model")
            val mac = TvProbe.macFor(adb, s, d.host)
            model to mac
        }
        update(d.id) { it.copy(model = model.ifBlank { it.model }, mac = mac ?: it.mac, kind = "tv") }
        "${d.name}: ADB autorizado ($model)${mac?.let { ", MAC $it salvo para ligar pela rede" } ?: ""}"
    } catch (e: AdbFailure) {
        "${d.name}: ${e.message}"
    }

    /** DIAL (smart TVs incl. Samsung/LG/Chromecast): launches an app such as YouTube with a payload (e.g. "v=ID"). */
    suspend fun dial(d: LanDeviceConfig, app: String, payload: String = ""): String = withContext(Dispatchers.IO) {
        val base = d.dialUrl.ifBlank { return@withContext "${d.name}: TV não anuncia DIAL" }.trimEnd('/')
        runCatching {
            http.newCall(Request.Builder().url("$base/$app").post(payload.toRequestBody(null)).build()).execute().use { r ->
                if (r.code in 200..299) "${d.name}: $app aberto pelo DIAL" else "FALHOU: ${d.name} recusou $app (HTTP ${r.code})"
            }
        }.getOrElse { "FALHOU: ${d.name}: ${it.message}" }
    }

    // ------------------------------------------------------------------ Bluetooth

    fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun canScanBluetooth() = bluetoothPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Classic discovery + BLE for [seconds]: speakers, TVs, headphones, remotes nearby. */
    @SuppressLint("MissingPermission")
    suspend fun scanBluetooth(seconds: Int = 10): List<BtDevice> {
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyList<BtDevice>().also { _status.value = "Sem Bluetooth neste aparelho" }
        if (!adapter.isEnabled) return emptyList<BtDevice>().also { _status.value = "Ligue o Bluetooth" }
        if (!canScanBluetooth()) return emptyList<BtDevice>().also { _status.value = "Permita o Bluetooth para procurar" }
        val found = linkedMapOf<String, BtDevice>()
        fun put(dev: BluetoothDevice, rssi: Int) {
            val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
            val type = when (dev.bluetoothClass?.majorDeviceClass) {
                0x0400 -> "áudio/vídeo"; 0x0100 -> "computador"; 0x0200 -> "telefone"; 0x0500 -> "periférico"; else -> "bluetooth"
            }
            found[dev.address] = BtDevice(name, dev.address, type, dev.bondState == BluetoothDevice.BOND_BONDED, rssi)
        }
        adapter.bondedDevices?.forEach { put(it, 0) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION") val dev: BluetoothDevice = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                put(dev, i.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt())
            }
        }
        val le = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = put(result.device, result.rssi)
        }
        _status.value = "Procurando Bluetooth…"
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        try {
            adapter.startDiscovery()
            adapter.bluetoothLeScanner?.startScan(le)
            delay(seconds * 1000L)
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { adapter.bluetoothLeScanner?.stopScan(le) }
            runCatching { context.unregisterReceiver(receiver) }
        }
        _bt.value = found.values.sortedByDescending { it.rssi }
        _status.value = "${found.size} aparelhos Bluetooth"
        return _bt.value
    }

    companion object {
        fun slug(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_')

        fun bestCheckPort(ports: List<Int>) = listOf(5555, 8009, 8002, 3000, 3389, 445, 22, 80).firstOrNull { it in ports } ?: 0

        /** First video of a YouTube search (no API key: reads the results page). */
        fun youtubeFirstVideo(http: OkHttpClient, query: String): String? = runCatching {
            val req = Request.Builder().url("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36")
                .header("Accept-Language", "pt-BR").build()
            http.newCall(req).execute().use { r -> videoIdIn(r.body?.string().orEmpty()) }
        }.getOrNull()

        fun videoIdIn(html: String): String? = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
    }
}
