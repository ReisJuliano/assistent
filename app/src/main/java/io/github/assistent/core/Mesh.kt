package io.github.assistent.core

import android.util.Log
import io.github.assistent.config.AppConfig
import io.github.assistent.config.ConfigStore
import io.github.assistent.config.MeshPeer
import io.github.assistent.data.MemoryEntity
import io.github.assistent.data.MemoryRepository
import io.github.assistent.data.PersonDao
import io.github.assistent.data.PersonEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

@Serializable data class Presence(val person: String, val room: String, val deviceId: String, val at: Long)
@Serializable data class JoinRequest(val code: String, val peer: MeshPeer)
@Serializable data class JoinResponse(
    val secret: String,
    val peers: List<MeshPeer>,
    val config: JsonObject,
    val people: List<PersonEntity>,
    val memories: List<MemoryEntity>,
)
@Serializable data class PairInfo(val host: String, val port: Int, val code: String, val expiresAt: Long) {
    /** Content of the QR code shown on screen. */
    val uri: String get() = "assistent://pair?h=$host&p=$port&c=$code"
}

/** Best LAN IPv4 of this device, null if offline. */
fun localIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}.getOrNull()

/**
 * Many devices, one robot. Devices pair with a QR code (one-time code shown by a device already set up);
 * afterwards they share settings, known faces and memories, tell each other who is in which room, and can
 * talk through each other ("avisa na cozinha que o jantar tá pronto"). Everything stays on the local network,
 * authenticated with a mesh secret.
 */
@OptIn(FlowPreview::class)
class Mesh(
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val people: PersonDao,
    private val memory: MemoryRepository,
    private val scope: CoroutineScope,
) {
    private val client = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val _presence = MutableStateFlow<Map<String, Presence>>(emptyMap())
    /** Last known room of each person (by name), across all devices. */
    val presence: StateFlow<Map<String, Presence>> = _presence
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private var pairing: PairInfo? = null

    /** True while the camera should look for a pairing QR code. */
    val scanning = MutableStateFlow(false)

    fun onQrScanned(text: String) {
        if (!scanning.value || !text.startsWith("assistent://pair")) return
        scanning.value = false
        scope.launch { join(text) }
    }
    @Volatile private var lastSharedHash = 0

    /** Hook for the runtime: speak something that came from another device. */
    var onRemoteSay: (String) -> Unit = {}
    var onPresenceSeen: (Presence) -> Unit = {}

    val paired get() = config.current.mesh.secret.isNotBlank()
    val self get() = config.current.let { MeshPeer(it.device.id, it.device.room, localIp() ?: "", it.api.port, System.currentTimeMillis()) }

    fun start() {
        lastSharedHash = shared(config.current).hashCode()
        // Push shared settings to the others whenever they change here.
        scope.launch {
            config.config.debounce(1_500).collect { cfg ->
                val sharedCfg = shared(cfg)
                val h = sharedCfg.hashCode()
                if (h != lastSharedHash && paired) {
                    lastSharedHash = h
                    broadcast("config", sharedCfg.toString())
                }
            }
        }
        // Heartbeat: keep each other's IP and room up to date.
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (paired) for (p in config.current.mesh.peers) hello(p)
                delay(60_000)
            }
        }
    }

    // ------------------------------------------------------------ pairing

    fun startPairing(): PairInfo {
        val code = (100000 + SecureRandom().nextInt(900000)).toString()
        val info = PairInfo(localIp() ?: "0.0.0.0", config.current.api.port, code, System.currentTimeMillis() + 5 * 60_000)
        pairing = info
        return info
    }

    /** Server side of pairing: validates the one-time code and hands over everything the new device needs. */
    suspend fun acceptJoin(req: JoinRequest): JoinResponse {
        val p = pairing ?: error("pairing not started")
        require(p.code == req.code && System.currentTimeMillis() < p.expiresAt) { "invalid or expired code" }
        pairing = null
        val secret = config.current.mesh.secret.ifBlank { ConfigStore.newToken() }
        val others = config.current.mesh.peers
        config.update { it.copy(mesh = it.mesh.copy(secret = secret, peers = (it.mesh.peers.filterNot { x -> x.id == req.peer.id } + req.peer))) }
        lastSharedHash = shared(config.current).hashCode()
        // Tell existing devices about the newcomer.
        scope.launch(Dispatchers.IO) { for (o in others) post(o, "peer", AppJson.encodeToString(MeshPeer.serializer(), req.peer)) }
        _status.value = "Pareado com ${req.peer.room}"
        return JoinResponse(secret, others + self, shared(config.current), people.all(), memory.recent(2000))
    }

    /** Client side: join the robot that shows the QR code. Accepts the QR content or host/port/code. */
    suspend fun join(qrOrHost: String, port: Int? = null, code: String? = null): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (h, pt, c) = if (qrOrHost.startsWith("assistent://")) {
                val q = qrOrHost.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                Triple(q.getValue("h"), q.getValue("p").toInt(), q.getValue("c"))
            } else Triple(qrOrHost, port ?: 8765, code ?: error("code required"))
            _status.value = "Conectando a $h…"
            val body = AppJson.encodeToString(JoinRequest.serializer(), JoinRequest(c, self))
            val text = client.newCall(
                Request.Builder().url("http://$h:$pt/api/v1/mesh/join").post(body.toRequestBody(EventDispatcher.JSON)).build(),
            ).execute().use { r -> r.body?.string().orEmpty().also { if (!r.isSuccessful) error("HTTP ${r.code}: ${it.take(120)}") } }
            val res = AppJson.decodeFromString(JoinResponse.serializer(), text)
            applyShared(res.config)
            config.update { it.copy(mesh = it.mesh.copy(secret = res.secret, peers = res.peers.filterNot { p -> p.id == it.device.id })) }
            importPeople(res.people)
            importMemories(res.memories)
            _status.value = "Pareado! Agora sou o mesmo robô dos outros ${res.peers.size} aparelho(s)."
            true
        }.getOrElse {
            Log.w(TAG, "join failed", it)
            _status.value = "Falha ao parear: ${it.message}"
            false
        }
    }

    fun leave() {
        config.update { it.copy(mesh = it.mesh.copy(secret = "", peers = emptyList())) }
        _status.value = "Desconectado do grupo"
    }

    // ------------------------------------------------------------ incoming (from LocalApiServer, already authenticated)

    fun checkSecret(secret: String?) = paired && secret == config.current.mesh.secret

    fun onHello(peer: MeshPeer): List<MeshPeer> {
        upsertPeer(peer)
        return config.current.mesh.peers + self
    }

    fun onPeer(peer: MeshPeer) = upsertPeer(peer)

    fun onConfig(shared: JsonObject) = applyShared(shared)

    fun onPresence(p: Presence) {
        _presence.value = _presence.value + (p.person.lowercase() to p)
        onPresenceSeen(p)
    }

    suspend fun onPerson(p: PersonEntity) = importPeople(listOf(p))
    suspend fun onMemory(m: MemoryEntity) = importMemories(listOf(m))
    fun onSay(text: String) = onRemoteSay(text)

    // ------------------------------------------------------------ outgoing

    /** "Diogo is in the kitchen": called when this device recognizes someone. */
    fun reportPresence(person: String) {
        val p = Presence(person, config.current.device.room, config.current.device.id, System.currentTimeMillis())
        val old = _presence.value[person.lowercase()]
        _presence.value = _presence.value + (person.lowercase() to p)
        // Only broadcast changes of room or every few minutes, not every frame.
        if (old == null || old.room != p.room || p.at - old.at > 3 * 60_000) broadcast("presence", AppJson.encodeToString(Presence.serializer(), p))
    }

    fun sharePerson(p: PersonEntity) = broadcast("person", AppJson.encodeToString(PersonEntity.serializer(), p))
    fun shareMemory(m: MemoryEntity) = broadcast("memory", AppJson.encodeToString(MemoryEntity.serializer(), m))

    /** Speak on the device in [room]. Returns false if no such room. */
    suspend fun sayInRoom(room: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (room.equals(config.current.device.room, ignoreCase = true)) { onRemoteSay(text); return@withContext true }
        val peer = config.current.mesh.peers.firstOrNull { it.room.equals(room, ignoreCase = true) } ?: return@withContext false
        post(peer, "say", AppJson.encodeToString(String.serializer(), text))
    }

    suspend fun broadcastSay(text: String): Int = withContext(Dispatchers.IO) {
        var delivered = 0
        onRemoteSay(text)
        delivered++
        for (peer in config.current.mesh.peers) {
            if (post(peer, "say", AppJson.encodeToString(String.serializer(), text))) delivered++
        }
        delivered
    }

    /** Where the person is now, if seen recently by any device. */
    fun whereIs(person: String, withinMs: Long = 15 * 60_000): Presence? =
        _presence.value[person.lowercase()]?.takeIf { System.currentTimeMillis() - it.at < withinMs }

    fun rooms(): List<String> = (listOf(config.current.device.room) + config.current.mesh.peers.map { it.room }).distinct()

    private fun broadcast(kind: String, json: String) {
        if (!paired) return
        scope.launch(Dispatchers.IO) { for (p in config.current.mesh.peers) post(p, kind, json) }
    }

    private fun post(peer: MeshPeer, kind: String, json: String): Boolean = runCatching {
        client.newCall(
            Request.Builder().url("http://${peer.host}:${peer.port}/api/v1/mesh/$kind")
                .header("X-Mesh-Secret", config.current.mesh.secret)
                .post(json.toRequestBody(EventDispatcher.JSON)).build(),
        ).execute().use { it.isSuccessful }
    }.getOrElse { Log.d(TAG, "mesh $kind -> ${peer.room} failed: ${it.message}"); false }

    private fun hello(peer: MeshPeer) = runCatching {
        val text = client.newCall(
            Request.Builder().url("http://${peer.host}:${peer.port}/api/v1/mesh/hello")
                .header("X-Mesh-Secret", config.current.mesh.secret)
                .post(AppJson.encodeToString(MeshPeer.serializer(), self).toRequestBody(EventDispatcher.JSON)).build(),
        ).execute().use { it.body?.string().orEmpty() }
        AppJson.decodeFromString(ListSerializer(MeshPeer.serializer()), text).forEach { if (it.id != config.current.device.id) upsertPeer(it) }
    }

    private fun upsertPeer(peer: MeshPeer) {
        if (peer.id == config.current.device.id) return
        config.update { c ->
            val list = c.mesh.peers.filterNot { it.id == peer.id } + peer.copy(lastSeen = maxOf(peer.lastSeen, System.currentTimeMillis()))
            c.copy(mesh = c.mesh.copy(peers = list))
        }
        lastSharedHash = shared(config.current).hashCode()
    }

    private fun applyShared(shared: JsonObject) {
        val merged = config.patch(shared)
        lastSharedHash = shared(merged).hashCode()
    }

    private suspend fun importPeople(list: List<PersonEntity>) {
        for (p in list) {
            val existing = people.byName(p.name)
            if (existing == null) people.insert(p.copy(id = 0)) else people.update(existing.copy(embeddings = p.embeddings, birthday = p.birthday.ifBlank { existing.birthday }))
        }
    }

    private suspend fun importMemories(list: List<MemoryEntity>) {
        val known = memory.recent(5000).map { it.content }.toHashSet()
        list.filter { it.content !in known }.forEach { memory.add(it.content, it.tags, source = "mesh", personId = it.personId, share = false) }
    }

    companion object {
        private const val TAG = "Mesh"

        /** Settings that are the same on every device: everything except this device's identity, API and mesh. */
        fun shared(cfg: AppConfig): JsonObject {
            val all = AppJson.encodeToJsonElement(cfg).jsonObject
            return JsonObject(all - setOf("device", "mesh", "api"))
        }
    }
}
