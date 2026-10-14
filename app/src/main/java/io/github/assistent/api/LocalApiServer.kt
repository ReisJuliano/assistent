package io.github.assistent.api

import android.util.Log
import io.github.assistent.AppGraph
import io.github.assistent.BuildConfig
import io.github.assistent.config.ApiConfig
import io.github.assistent.config.AppConfig
import io.github.assistent.config.WebhookDef
import io.github.assistent.core.Ambient
import io.github.assistent.config.MeshPeer
import io.github.assistent.core.JoinRequest
import io.github.assistent.core.Presence
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import io.github.assistent.core.AppEvent
import io.github.assistent.face.Scene
import io.github.assistent.core.AppJson
import io.github.assistent.core.Emotion
import io.github.assistent.tools.WebhookTool
import io.github.assistent.tools.parseDateTime
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

@Serializable data class ChatBody(val text: String, val speak: Boolean = true)
@Serializable data class SayBody(val text: String, val emotion: String? = null)
@Serializable data class EmotionBody(val emotion: String, val holdMs: Long = 9_000)
@Serializable data class MemoryBody(val content: String, val tags: String = "", val personId: Long? = null, val person: String? = null)
@Serializable data class TimerBody(val label: String = "timer", val seconds: Double? = null, val minutes: Double? = null, val personId: Long? = null, val room: String = "")
@Serializable data class EventBody(
    val title: String,
    val start: String,
    val end: String? = null,
    val allDay: Boolean = false,
    val location: String = "",
    val notes: String = "",
    val personId: Long? = null,
    val remindMinutesBefore: Int = 0,
    val recurrence: String = "none",
)
@Serializable data class EventPatch(
    val title: String? = null,
    val start: String? = null,
    val end: String? = null,
    val allDay: Boolean? = null,
    val location: String? = null,
    val notes: String? = null,
    val personId: Long? = null,
    val remindMinutesBefore: Int? = null,
    val recurrence: String? = null,
)
@Serializable data class MessageBody(val to: String? = null, val text: String, val from: String = "")
@Serializable data class ListItemBody(val text: String, val qty: String = "", val addedBy: String = "", val personId: Long? = null)
@Serializable data class ListCheckBody(val done: Boolean = true)
@Serializable data class CompanionTokenBody(val name: String = "Celular", val personId: Long? = null, val scopes: String = "chat,lists,events,messages,reminders,timers")
@Serializable data class VoiceEnrollBody(val personId: Long)
@Serializable data class AlertBody(val source: String, val kind: String, val text: String, val imageUrl: String = "", val room: String = "")
@Serializable data class ToolBody(val args: JsonObject = JsonObject(emptyMap()))
@Serializable data class ErrorBody(val error: String)
@Serializable data class SceneBody(val scene: String)
@Serializable data class EnrollBody(val name: String)
@Serializable data class PersonPatch(val name: String? = null, val birthday: String? = null, val notes: String? = null)
@Serializable data class BubbleBody(val text: String, val speak: Boolean = false)
@Serializable data class JoinBody(val qr: String? = null, val host: String? = null, val port: Int? = null, val code: String? = null)
@Serializable data class BroadcastBody(val text: String)
@Serializable data class CallRoomBody(val room: String)

/**
 * REST API on the phone, so anything can be configured or triggered from a PC, Home Assistant,
 * Tasker, scripts… Every route except `/api/v1/health` needs `Authorization: Bearer <token>`.
 * Restarts itself whenever `api` settings change. See docs/API.md.
 */
class LocalApiServer(private val g: AppGraph) {
    private var server: EmbeddedServer<*, *>? = null
    private var runningConfig: ApiConfig? = null

    fun start() {
        g.scope.launch {
            g.config.config.map { it.api }.distinctUntilChanged().collectLatest {
                // Let the config request finish before replacing the server that received it.
                delay(250)
                restart(it)
            }
        }
    }

    @Synchronized
    private fun restart(cfg: ApiConfig) {
        if (runningConfig == cfg && server != null) return
        server?.stop(300, 1_000)
        server = null
        runningConfig = cfg
        if (!cfg.enabled) return
        val host = if (cfg.allowLan) "0.0.0.0" else "127.0.0.1"
        server = runCatching {
            embeddedServer(CIO, port = cfg.port, host = host) { module() }.start(wait = false)
        }.onFailure { Log.e(TAG, "API failed to start on $host:${cfg.port}", it) }.getOrNull()
        Log.i(TAG, "API listening on $host:${cfg.port}")
    }

    private suspend fun authorized(ctx: RoutingContext): Boolean {
        val token = g.config.current.api.token
        val header = ctx.call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val given = header ?: ctx.call.request.queryParameters["token"] ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray()) || g.companionTokens.accepts(given)
    }

    private fun guard(body: suspend RoutingContext.() -> Unit): suspend RoutingContext.() -> Unit = {
        if (authorized(this)) body() else call.respond(HttpStatusCode.Unauthorized, ErrorBody("missing or invalid token"))
    }

    private fun io.ktor.server.application.Application.module() {
        install(ContentNegotiation) { json(AppJson) }
        install(WebSockets)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ErrorBody(cause.message ?: cause.javaClass.simpleName))
            }
        }
        install(CORS) {
            anyHost()
            allowHeader("Authorization"); allowHeader("Content-Type")
            allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Patch); allowMethod(HttpMethod.Delete)
        }
        routing {
            get("/") { call.respondText(WebPanel.HTML, ContentType.Text.Html) }

            // ------------------------------------------------------------ device-to-device (mesh secret / pairing code)
            route("/api/v1/mesh") {
                post("/join") { call.respond(g.mesh.acceptJoin(call.receive<JoinRequest>())) }
                post("/{kind}") {
                    if (!g.mesh.checkSecret(call.request.headers["X-Mesh-Secret"])) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorBody("bad mesh secret")); return@post
                    }
                    val text = call.receiveText()
                    when (call.parameters["kind"]) {
                        "hello" -> call.respond(g.mesh.onHello(AppJson.decodeFromString(MeshPeer.serializer(), text)))
                        "peer" -> { g.mesh.onPeer(AppJson.decodeFromString(MeshPeer.serializer(), text)); call.respond(ok()) }
                        "config" -> { g.mesh.onConfig(AppJson.parseToJsonElement(text) as JsonObject); call.respond(ok()) }
                        "presence" -> { g.mesh.onPresence(AppJson.decodeFromString(Presence.serializer(), text)); call.respond(ok()) }
                        "person" -> { g.mesh.onPerson(AppJson.decodeFromString(io.github.assistent.data.PersonEntity.serializer(), text)); call.respond(ok()) }
                        "memory" -> { g.mesh.onMemory(AppJson.decodeFromString(io.github.assistent.data.MemoryEntity.serializer(), text)); call.respond(ok()) }
                        "say" -> { g.mesh.onSay(AppJson.decodeFromString(String.serializer(), text)); call.respond(ok()) }
                        else -> call.respond(HttpStatusCode.NotFound, ErrorBody("unknown"))
                    }
                }
            }

            route("/api/v1") {
                get("/health") {
                    call.respond(buildJsonObject {
                        put("ok", true); put("name", g.config.current.personality.name); put("version", BuildConfig.VERSION_NAME)
                    })
                }

                // ---------------------------------------------------------- state & control
                get("/state", guard {
                    call.respond(buildJsonObject {
                        put("state", AppJson.encodeToJsonElement(g.runtime.state.value))
                        put("gaze", AppJson.encodeToJsonElement(g.runtime.gaze.value))
                        put("currentPerson", AppJson.encodeToJsonElement(g.presence.currentPerson.value))
                    })
                })
                get("/devices", guard { call.respond(g.config.current.lanDevices) })
                post("/devices/discover", guard { call.respond(g.home.scan()) })
                // Full network scan (mDNS, SSDP/UPnP, port sweep of the /24, NetBIOS name+MAC) and its last result.
                post("/network/scan", guard { call.respond(g.home.scan()) })
                get("/network/found", guard { call.respond(g.home.found.value) })
                // Save a found device: {"host":"192.168.1.20","name":"TV da Sala","room":"Sala"}.
                post("/devices/add", guard {
                    val b = AppJson.parseToJsonElement(call.receiveText()) as JsonObject
                    fun f(k: String) = (b[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    val found = g.home.found.value.firstOrNull { it.host == f("host") }
                        ?: io.github.assistent.tools.NetDevice(f("host"), kind = f("kind").ifBlank { "tv" }, mac = f("mac"),
                            ports = listOf(5555).filter { io.github.assistent.tools.NetworkScanner.isOpen(f("host"), it, 600) })
                    call.respond(g.home.add(found.copy(mac = f("mac").ifBlank { found.mac }), f("name").ifBlank { found.name }, f("room")))
                })
                post("/devices/{id}/wake", guard {
                    val d = g.home.find(call.parameters["id"]!!) ?: error("not found")
                    call.respond(buildJsonObject { put("result", g.home.wake(d)) })
                })
                post("/devices/{id}/adb", guard {
                    val d = g.home.find(call.parameters["id"]!!) ?: error("not found")
                    call.respond(buildJsonObject { put("result", g.home.authorizeAdb(d)) })
                })
                get("/devices/{id}/online", guard {
                    val d = g.home.find(call.parameters["id"]!!) ?: error("not found")
                    call.respond(buildJsonObject { put("online", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { g.home.online(d) }) })
                })
                delete("/devices/{id}", guard { g.home.remove(call.parameters["id"]!!); call.respond(ok()) })
                post("/bluetooth/scan", guard { call.respond(g.home.scanBluetooth(10)) })
                post("/devices/{id}/status", guard {
                    val id = call.parameters["id"] ?: error("missing device id")
                    val tool = g.tools.find("tv_control") ?: error("tv_control unavailable")
                    val result = tool.execute(buildJsonObject { put("device", id); put("action", "status") })
                    call.respond(buildJsonObject { put("result", result) })
                })
                post("/devices/{id}/control", guard {
                    val id = call.parameters["id"] ?: error("missing device id")
                    val body = call.receive<ToolBody>().args
                    val tool = g.tools.find("tv_control") ?: error("tv_control unavailable")
                    val args = buildJsonObject {
                        put("device", id)
                        body.forEach { (key, value) -> if (key != "device") put(key, value) }
                    }
                    call.respond(buildJsonObject { put("result", tool.execute(args)) })
                })
                post("/chat", guard {
                    val b = call.receive<ChatBody>()
                    val r = g.runtime.chat(b.text, b.speak)
                    call.respond(buildJsonObject { put("reply", r.text); put("emotion", r.emotion.tag) })
                })
                post("/say", guard {
                    val b = call.receive<SayBody>()
                    g.scope.launch { g.runtime.say(b.text, Emotion.fromTag(b.emotion)) }
                    call.respond(ok())
                })
                post("/listen", guard { g.runtime.manualTrigger.trySend(Unit); call.respond(ok()) })
                post("/emotion", guard {
                    val b = call.receive<EmotionBody>()
                    val e = Emotion.fromTag(b.emotion) ?: error("unknown emotion; use: ${Emotion.entries.joinToString { it.tag }}")
                    g.runtime.setEmotion(e, b.holdMs)
                    call.respond(ok())
                })
                post("/sleep", guard { g.runtime.sleep(); call.respond(ok()) })
                post("/wake", guard { g.runtime.wake(); call.respond(ok()) })
                post("/stop", guard { g.runtime.interruptSpeaking(); call.respond(ok()) })
                post("/history/clear", guard { g.brain.clearHistory(); call.respond(ok()) })

                // ---------------------------------------------------------- life: scenes & weather ambience
                get("/scenes", guard {
                    call.respond(Scene.entries.map { sc ->
                        buildJsonObject { put("key", sc.key); put("label", sc.label); put("kind", sc.kind.name.lowercase()); put("seconds", sc.seconds) }
                    })
                })
                // Screen as a light: {"on":true,"style":"lamp|phone|bulb|random","seconds":300} or {"on":false}.
                post("/light", guard {
                    val b = AppJson.parseToJsonElement(call.receiveText()) as JsonObject
                    val on = (b["on"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false"
                    val style = (b["style"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "random"
                    val secs = (b["seconds"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 300
                    call.respond(buildJsonObject { put("result", if (on) g.runtime.lightOn(style, secs) else g.runtime.lightOff()) })
                })
                // "Sign in with ChatGPT": status / start the browser sign-in on the device / sign out.
                get("/chatgpt", guard {
                    call.respond(buildJsonObject { put("signedIn", g.chatgpt.signedIn); put("status", g.chatgpt.status.value) })
                })
                post("/chatgpt/signin", guard {
                    g.chatgpt.startSignIn()
                    call.respond(buildJsonObject { put("status", g.chatgpt.status.value) })
                })
                post("/chatgpt/signout", guard { g.chatgpt.signOut(); call.respond(ok()) })
                post("/scene", guard {
                    val sc = Scene.of(call.receive<SceneBody>().scene) ?: error("unknown scene; GET /scenes")
                    when (sc.kind) {
                        Scene.Kind.DREAM -> g.runtime.sleep()
                        else -> g.runtime.wake(greet = false)
                    }
                    if (io.github.assistent.face.LightGag.isLight(sc)) g.runtime.lightOn(sc.key.removePrefix("light_"), 15)
                    else g.runtime.life.play(sc)
                    call.respond(ok())
                })
                delete("/scene", guard { g.runtime.life.clear(); call.respond(ok()) })
                post("/bubble", guard {
                    val b = call.receive<BubbleBody>()
                    g.runtime.showBubble(b.text, b.speak)
                    call.respond(ok())
                })

                // ---------------------------------------------------------- people (face recognition)
                get("/people", guard {
                    call.respond(g.people.list().map { p ->
                        buildJsonObject { put("id", p.id); put("name", p.name); put("birthday", p.birthday); put("notes", p.notes); put("lastSeen", p.lastSeen) }
                    })
                })
                post("/people/enroll", guard {
                    // Captures the face in front of the camera for a few seconds. The app must be open with the camera on.
                    val p = g.people.enroll(call.receive<EnrollBody>().name) ?: error(g.people.status.value.ifBlank { "no face captured" })
                    call.respond(buildJsonObject { put("id", p.id); put("name", p.name) })
                })
                patch("/people/{id}", guard {
                    val id = call.parameters["id"]!!.toLong()
                    val b = call.receive<PersonPatch>()
                    val p = g.people.list().firstOrNull { it.id == id } ?: error("not found")
                    g.people.update(p.copy(name = b.name ?: p.name, birthday = b.birthday ?: p.birthday, notes = b.notes ?: p.notes))
                    call.respond(ok())
                })
                delete("/people/{id}", guard { call.respond(buildJsonObject { put("deleted", g.people.delete(call.parameters["id"]!!.toLong())) }) })
                post("/greet/test", guard { g.greeter.testGreeting(); call.respond(ok()) })
                get("/update", guard {
                    call.respond(buildJsonObject {
                        put("versionName", BuildConfig.VERSION_NAME); put("versionCode", BuildConfig.VERSION_CODE)
                        put("status", g.updater.status.value); put("canInstall", g.updater.canInstall()); put("canRelaunch", g.updater.canRelaunch())
                    })
                })
                post("/update/check", guard { call.respond(buildJsonObject { put("result", g.updater.checkAndInstall()) }) })
                // Simulates camera motion (asleep: at most a peek; it never wakes up from motion).
                post("/motion", guard { g.runtime.onMotion(); call.respond(ok()) })
                // AI-written phrase pools (greetings, scares, quips, facts, sleep talk…)
                get("/phrases", guard {
                    call.respond(buildJsonObject {
                        g.phrases.all().forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
                    })
                })
                delete("/phrases", guard { g.phrases.clear(call.request.queryParameters["category"]); call.respond(ok()) })

                // ---------------------------------------------------------- multi-device mesh (user side, token auth)
                get("/mesh", guard {
                    call.respond(buildJsonObject {
                        put("paired", g.mesh.paired)
                        put("self", AppJson.encodeToJsonElement(MeshPeer.serializer(), g.mesh.self))
                        put("peers", AppJson.encodeToJsonElement(ListSerializer(MeshPeer.serializer()), g.config.current.mesh.peers))
                        put("presence", AppJson.encodeToJsonElement(ListSerializer(Presence.serializer()), g.mesh.presence.value.values.toList()))
                    })
                })
                post("/mesh/pair", guard {
                    val info = g.mesh.startPairing()
                    call.respond(buildJsonObject { put("qr", info.uri); put("host", info.host); put("port", info.port); put("code", info.code) })
                })
                post("/mesh/connect", guard {
                    val b = call.receive<JoinBody>()
                    val ok = g.mesh.join(b.qr ?: b.host ?: error("qr or host required"), b.port, b.code)
                    call.respond(buildJsonObject { put("ok", ok); put("status", g.mesh.status.value) })
                })
                post("/mesh/leave", guard { g.mesh.leave(); call.respond(ok()) })
                post("/mesh/broadcast", guard {
                    val count = g.mesh.broadcastSay(call.receive<BroadcastBody>().text)
                    call.respond(buildJsonObject { put("devices", count) })
                })
                post("/mesh/call", guard {
                    val room = call.receive<CallRoomBody>().room
                    g.runtime.life.play(Scene.INTERCOM)
                    call.respond(buildJsonObject { put("ok", g.mesh.sayInRoom(room, "Chamando pelo interfone. Pode falar comigo por aqui.")) })
                })
                post("/ambient", guard {
                    // Override the real weather for testing: {"condition":"rain","temperature":8}
                    g.runtime.life.setAmbient(call.receive<Ambient>())
                    call.respond(ok())
                })

                get("/events", guard {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        write(": connected\n\n"); flush()
                        g.events.events.collect { e ->
                            write("event: ${e.event}\ndata: ${AppJson.encodeToString(AppEvent.serializer(), e)}\n\n")
                            flush()
                        }
                    }
                })

                webSocket("/ws") {
                    val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
                        ?: call.request.queryParameters["token"].orEmpty()
                    if (!MessageDigest.isEqual(token.toByteArray(), g.config.current.api.token.toByteArray()) && !g.companionTokens.accepts(token)) {
                        close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "bad token"))
                        return@webSocket
                    }
                    val eventsJob = launch {
                        g.events.events.collect { e ->
                            send(Frame.Text(AppJson.encodeToString(AppEvent.serializer(), e)))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val obj = runCatching { AppJson.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                            when (obj["type"]?.toString()?.trim('"')) {
                                "chat" -> {
                                    val text = obj["text"]?.toString()?.trim('"').orEmpty()
                                    if (text.isNotBlank()) {
                                        val reply = g.runtime.chat(text, speak = obj["speak"]?.toString() != "false")
                                        send(Frame.Text(buildJsonObject { put("event", "chat_reply"); put("text", reply.text); put("emotion", reply.emotion.tag) }.toString()))
                                    }
                                }
                                "say" -> obj["text"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }?.let { g.runtime.say(it) }
                                "ping" -> send(Frame.Text("""{"event":"pong"}"""))
                            }
                        }
                    } finally {
                        eventsJob.cancel()
                    }
                }

                // ---------------------------------------------------------- config
                get("/config", guard { call.respond(g.config.current) })
                put("/config", guard { g.config.replace(call.receive<AppConfig>()); call.respond(ok()) })
                patch("/config", guard {
                    val patch = call.receive<JsonObject>()
                    g.config.patch(patch)
                    call.respond(ok())
                })
                post("/config/token/regenerate", guard {
                    call.respond(buildJsonObject { put("token", g.config.regenerateToken()) })
                })
                get("/llm/models", guard { call.respond(g.llm.listModels(g.config.current.llm)) })

                // ---------------------------------------------------------- memory
                get("/memories", guard {
                    val q = call.request.queryParameters["q"]
                    val person = call.request.queryParameters["person"]
                    val personId = call.request.queryParameters["personId"]?.toLongOrNull()
                        ?: person?.takeIf { it.isNotBlank() }?.let { g.memory.personIdByName(it) }
                    call.respond(if (q.isNullOrBlank()) {
                        if (personId == null) g.memory.recent(500) else g.memory.recentForPrompt(personId, 500)
                    } else {
                        g.memory.search(q, 50, personId)
                    })
                })
                post("/memories", guard {
                    val b = call.receive<MemoryBody>()
                    val personId = b.personId ?: b.person?.takeIf { it.isNotBlank() }?.let { g.memory.personIdByName(it) }
                    call.respond(buildJsonObject { put("id", g.memory.add(b.content, b.tags, source = "api", personId = personId)) })
                })
                delete("/memories/{id}", guard {
                    call.respond(buildJsonObject { put("deleted", g.memory.delete(call.parameters["id"]!!.toLong())) })
                })
                delete("/memories", guard { g.memory.clear(); call.respond(ok()) })

                // ---------------------------------------------------------- reminders
                get("/reminders", guard {
                    call.respond(g.reminders.list(onlyActive = call.request.queryParameters["all"] != "true"))
                })
                post("/reminders", guard {
                    // Same body as the create_reminder tool: message, datetime | in_minutes, kind, repeat.
                    val args = AppJson.parseToJsonElement(call.receiveText()) as JsonObject
                    call.respond(buildJsonObject { put("result", g.tools.find("create_reminder")!!.execute(args)) })
                })
                delete("/reminders/{id}", guard {
                    call.respond(buildJsonObject { put("deleted", g.reminders.cancel(call.parameters["id"]!!.toLong())) })
                })
                // The line it would say for this reminder right now (doesn't fire it).
                get("/reminders/{id}/preview", guard {
                    val r = g.db.reminders().get(call.parameters["id"]!!.toLong()) ?: error("not found")
                    val owner = r.personId?.let { g.db.people().get(it)?.name } ?: g.config.current.personality.userName
                    call.respond(buildJsonObject { put("text", g.runtime.reminderLine(r, owner)) })
                })
                post("/reminders/{id}/ack", guard {
                    call.respond(buildJsonObject { put("acknowledged", g.reminders.acknowledge(call.parameters["id"]!!.toLong())) })
                })

                // ---------------------------------------------------------- timers
                get("/timers", guard {
                    call.respond(g.timers.list(all = call.request.queryParameters["all"] == "true"))
                })
                post("/timers", guard {
                    val b = call.receive<TimerBody>()
                    val seconds = b.seconds ?: b.minutes?.times(60) ?: error("seconds or minutes required")
                    val timer = g.timers.create(b.label, (seconds * 1000).toLong(), b.personId, b.room)
                    call.respond(timer)
                })
                delete("/timers/{id}", guard {
                    call.respond(buildJsonObject { put("deleted", g.timers.cancel(call.parameters["id"]!!.toLong())) })
                })

                // ---------------------------------------------------------- calendar
                get("/calendar/events", guard {
                    val from = call.request.queryParameters["from"]?.let(::parseDateTime)
                        ?: (System.currentTimeMillis() - 24 * 60 * 60_000L)
                    val to = call.request.queryParameters["to"]?.let(::parseDateTime)
                        ?: (System.currentTimeMillis() + 30L * 24 * 60 * 60_000L)
                    call.respond(g.calendar.between(from, to, call.request.queryParameters["personId"]?.toLongOrNull()))
                })
                post("/calendar/events", guard {
                    val b = call.receive<EventBody>()
                    val event = g.calendar.add(
                        io.github.assistent.data.EventEntity(
                            title = b.title,
                            startAt = parseDateTime(b.start) ?: error("invalid start"),
                            endAt = b.end?.let { parseDateTime(it) },
                            allDay = b.allDay,
                            location = b.location,
                            notes = b.notes,
                            personId = b.personId,
                            remindMinutesBefore = b.remindMinutesBefore,
                            recurrence = b.recurrence,
                        ),
                    )
                    call.respond(event)
                })
                patch("/calendar/events/{id}", guard {
                    val existing = g.calendar.get(call.parameters["id"]!!.toLong()) ?: error("not found")
                    val b = call.receive<EventPatch>()
                    val updated = existing.copy(
                        title = b.title ?: existing.title,
                        startAt = b.start?.let { parseDateTime(it) ?: error("invalid start") } ?: existing.startAt,
                        endAt = b.end?.let { parseDateTime(it) } ?: existing.endAt,
                        allDay = b.allDay ?: existing.allDay,
                        location = b.location ?: existing.location,
                        notes = b.notes ?: existing.notes,
                        personId = b.personId ?: existing.personId,
                        remindMinutesBefore = b.remindMinutesBefore ?: existing.remindMinutesBefore,
                        recurrence = b.recurrence ?: existing.recurrence,
                    )
                    g.calendar.update(updated)
                    call.respond(updated)
                })
                delete("/calendar/events/{id}", guard {
                    call.respond(buildJsonObject { put("deleted", g.calendar.delete(call.parameters["id"]!!.toLong())) })
                })

                // ---------------------------------------------------------- person messages
                get("/messages", guard { call.respond(g.messages.pending()) })
                post("/messages", guard {
                    val b = call.receive<MessageBody>()
                    call.respond(g.messages.leave(b.to, b.text, b.from))
                })
                delete("/messages/{id}", guard {
                    call.respond(buildJsonObject { put("deleted", g.messages.delete(call.parameters["id"]!!.toLong())) })
                })

                // ---------------------------------------------------------- lists
                get("/lists", guard { call.respond(g.lists.allLists()) })
                get("/lists/{name}", guard { call.respond(g.lists.items(call.parameters["name"]!!)) })
                post("/lists/{name}/items", guard {
                    val b = call.receive<ListItemBody>()
                    call.respond(g.lists.add(call.parameters["name"]!!, b.text, b.qty, b.addedBy, b.personId))
                })
                patch("/lists/items/{id}", guard {
                    val b = call.receive<ListCheckBody>()
                    call.respond(buildJsonObject { put("updated", g.lists.check(call.parameters["id"]!!.toLong(), b.done)) })
                })
                delete("/lists/items/{id}", guard {
                    call.respond(buildJsonObject { put("deleted", g.lists.remove(call.parameters["id"]!!.toLong())) })
                })

                // ---------------------------------------------------------- companion app tokens
                get("/companion/tokens", guard { call.respond(g.companionTokens.list()) })
                post("/companion/tokens", guard {
                    val b = call.receive<CompanionTokenBody>()
                    val (entity, token) = g.companionTokens.create(b.name, b.personId, b.scopes)
                    call.respond(buildJsonObject { put("token", token); put("device", AppJson.encodeToJsonElement(entity)) })
                })
                post("/companion/pair", guard {
                    val b = call.receive<CompanionTokenBody>()
                    val (_, token) = g.companionTokens.create(b.name, b.personId, b.scopes)
                    val host = g.mesh.self.host.ifBlank { "127.0.0.1" }
                    val base = "http://$host:${g.config.current.api.port}/api/v1"
                    call.respond(buildJsonObject {
                        put("token", token)
                        put("baseUrl", base)
                        put("uri", "assistent-companion://pair?base=${java.net.URLEncoder.encode(base, "UTF-8")}&token=$token")
                    })
                })
                delete("/companion/tokens/{id}", guard {
                    call.respond(buildJsonObject { put("revoked", g.companionTokens.revoke(call.parameters["id"]!!.toLong())) })
                })

                // ---------------------------------------------------------- sleep, quizzes, voice identity, house alerts
                get("/sleep/report", guard {
                    call.respond(buildJsonObject { put("report", g.sleep.report(call.request.queryParameters["days"]?.toIntOrNull() ?: 7)) })
                })
                get("/quiz/scores", guard { call.respond(g.quizzes.all()) })
                // Voice ID from an uploaded WAV (16 kHz mono 16-bit): enroll a person or identify the speaker.
                post("/voice/enroll-audio", guard {
                    val personId = call.request.queryParameters["personId"]?.toLong() ?: error("personId required")
                    val audio = io.github.assistent.voice.Wav.decode16kMono(call.receive<ByteArray>())
                    call.respond(buildJsonObject { put("result", g.voiceIdentity.enroll(personId, audio)) })
                })
                // What the robot sees in an image (JPEG/PNG body): COCO objects with boxes in 0..1.
                post("/vision/detect", guard {
                    val bytes = call.receive<ByteArray>()
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not an image")
                    val found = g.body.detectOnce(bmp)
                    call.respond(kotlinx.serialization.json.buildJsonArray {
                        found.forEach { o ->
                            add(buildJsonObject {
                                put("label", o.label); put("score", o.score)
                                put("box", "%.2f,%.2f,%.2f,%.2f".format(o.box.left, o.box.top, o.box.right, o.box.bottom))
                            })
                        }
                    })
                })
                // Voice games: {"game":"quiz|numero|pedra_papel_tesoura|verdade_ou_mentira","topic":"..."}; scores per person.
                post("/games/start", guard {
                    val b = AppJson.parseToJsonElement(call.receiveText()) as JsonObject
                    val game = (b["game"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "quiz"
                    val topic = (b["topic"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    val voice = (b["voice"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false"
                    if (voice) {
                        g.games.request(game.replace('_', ' '), topic)
                        g.scope.launch { g.runtime.wake(greet = false); g.runtime.playGame() }
                        call.respond(ok())
                    } else {
                        // Driven by /games/answer (tests, companion app): no speaking, no listening.
                        val t = g.games.begin(game.replace('_', ' '), topic)
                        call.respond(buildJsonObject { put("say", t.say); put("show", t.show); put("done", t.done) })
                    }
                })
                post("/games/answer", guard {
                    val text = (AppJson.parseToJsonElement(call.receiveText()) as JsonObject)["text"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
                    val turn = g.games.handle(text)
                    call.respond(buildJsonObject { put("say", turn?.say ?: "nenhum jogo ativo"); put("done", turn?.done ?: true); put("show", turn?.show.orEmpty()) })
                })
                get("/games", guard {
                    call.respond(buildJsonObject {
                        put("active", g.games.session?.name ?: "")
                        put("scores", AppJson.encodeToJsonElement(g.quizzes.all()))
                    })
                })
                // Spotify account: status / browser login on the device / log out.
                get("/spotify", guard { call.respond(buildJsonObject { put("connected", g.spotify.connected); put("status", g.spotify.status.value); put("redirectUri", io.github.assistent.tools.SpotifyAuth.REDIRECT) }) })
                post("/spotify/signin", guard { call.respond(buildJsonObject { put("status", g.spotify.startSignIn()) }) })
                post("/spotify/signout", guard { g.spotify.signOut(); call.respond(ok()) })
                // What the robot is doing right now (tool calls with their real state) and cancel-all ("parar").
                get("/operations", guard { call.respond(g.operations.list.value) })
                post("/operations/cancel", guard { call.respond(buildJsonObject { put("cancelled", g.operations.cancelAll()) }) })
                // Wake phrase self-test: speaks {"text"} with the system voice (or takes a WAV body) and runs the
                // wake detector on it. Shows whether the name is in the recognizer's vocabulary and how sure it is.
                post("/wake/selftest", guard {
                    val bytes = call.receive<ByteArray>()
                    val audio = if (bytes.size > 4 && String(bytes, 0, 4) == "RIFF") io.github.assistent.voice.Wav.decode16kMono(bytes) else {
                        val text = (AppJson.parseToJsonElement(String(bytes, Charsets.UTF_8)) as JsonObject)["text"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                            ?: error("send {\"text\":\"ei nome\"} or a WAV")
                        val clip = g.speaker.synthesize(text) ?: error("TTS unavailable")
                        io.github.assistent.voice.Wav.pcmTo16k(clip.pcm, clip.sampleRate)
                    }
                    call.respond(g.wakeWord.detectOffline(audio))
                })
                get("/wake/names", guard {
                    g.wakeWord.ensureModel()
                    call.respond(buildJsonObject {
                        put("name", g.config.current.personality.name)
                        put("unknownWords", kotlinx.serialization.json.JsonArray(g.wakeWord.unknownNameWords().orEmpty().map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        put("suggestions", kotlinx.serialization.json.JsonArray(g.wakeWord.goodNames().map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    })
                })
                // Hand gesture in an image (JPEG/PNG body): gesture name, score and palm position.
                post("/vision/hands", guard {
                    val bytes = call.receive<ByteArray>()
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not an image")
                    call.respond(buildJsonObject { put("result", g.hands.recognizeOnce(bmp) ?: "nenhuma mão") })
                })
                // What the robot hears in a WAV clip (YAMNet groups and scores).
                post("/sounds/classify", guard {
                    val r = g.ears.classifyClip(io.github.assistent.voice.Wav.decode16kMono(call.receive<ByteArray>()))
                    call.respond(buildJsonObject { r.forEach { (k, v) -> put(k, v) } })
                })
                post("/voice/identify", guard {
                    val m = g.voiceIdentity.identify(io.github.assistent.voice.Wav.decode16kMono(call.receive<ByteArray>()))
                    call.respond(buildJsonObject { put("person", m.person?.name ?: ""); put("score", m.score) })
                })
                post("/voice/enroll", guard {
                    // Speak normally for ~10 s after calling this (the robot must be idle, listening for its name).
                    val body = call.receive<VoiceEnrollBody>()
                    val audio = g.wakeWord.captureVoice(10, 40_000) ?: error("não ouvi voz suficiente (o robô precisa estar ocioso)")
                    call.respond(buildJsonObject { put("result", g.voiceIdentity.enroll(body.personId, audio)) })
                })
                get("/alerts", guard { call.respond(g.alerts.recent()) })
                post("/alerts", guard {
                    val b = call.receive<AlertBody>()
                    call.respond(g.alerts.create(b.source, b.kind, b.text, b.imageUrl, b.room))
                })

                // ---------------------------------------------------------- webhooks (custom actions)
                get("/webhooks", guard { call.respond(g.config.current.webhooks) })
                post("/webhooks", guard {
                    val def = call.receive<WebhookDef>()
                    require(def.id.isNotBlank()) { "id required" }
                    g.config.update { c -> c.copy(webhooks = c.webhooks.filterNot { it.id == def.id } + def) }
                    call.respond(def)
                })
                delete("/webhooks/{id}", guard {
                    val id = call.parameters["id"]!!
                    g.config.update { c -> c.copy(webhooks = c.webhooks.filterNot { it.id == id }) }
                    call.respond(ok())
                })
                post("/webhooks/{id}/run", guard {
                    val id = call.parameters["id"]!!
                    val def = g.config.current.webhooks.firstOrNull { it.id == id } ?: error("webhook $id not found")
                    val args = call.receive<ToolBody>().args
                    call.respond(buildJsonObject { put("result", WebhookTool(g.http, def).execute(args)) })
                })

                // ---------------------------------------------------------- tools
                get("/tools", guard {
                    call.respond(g.tools.all().map { t ->
                        buildJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.parameters) }
                    })
                })
                post("/tools/{name}", guard {
                    val tool = g.tools.find(call.parameters["name"]!!) ?: error("unknown tool")
                    call.respond(buildJsonObject { put("result", g.tools.run(tool, call.receive<ToolBody>().args)) })
                })
            }
        }
    }

    private fun ok() = buildJsonObject { put("ok", true) }

    companion object {
        private const val TAG = "LocalApi"
    }
}
