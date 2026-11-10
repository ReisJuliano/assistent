package io.github.assistent

import android.app.Application
import android.content.Context
import io.github.assistent.api.LocalApiServer
import io.github.assistent.alerts.AlertManager
import io.github.assistent.brain.Brain
import io.github.assistent.brain.LlmClient
import io.github.assistent.brain.NeuralBrainClient
import io.github.assistent.calendar.CalendarRepository
import io.github.assistent.companion.CompanionTokenManager
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AssistantRuntime
import io.github.assistent.core.AmbientBuffer
import io.github.assistent.core.Emotion
import io.github.assistent.core.EmotionEngine
import io.github.assistent.core.ProactiveCache
import io.github.assistent.core.EventDispatcher
import io.github.assistent.core.Catchphrases
import io.github.assistent.core.PhrasePool
import io.github.assistent.core.Greeter
import io.github.assistent.core.Interactions
import io.github.assistent.core.Mesh
import io.github.assistent.core.PresenceTracker
import io.github.assistent.tools.ListRoomsTool
import io.github.assistent.tools.SayInRoomTool
import io.github.assistent.tools.WhereIsTool
import io.github.assistent.tools.BroadcastTool
import io.github.assistent.tools.CallRoomTool
import kotlinx.coroutines.launch
import io.github.assistent.vision.People
import io.github.assistent.data.AppDatabase
import io.github.assistent.data.MemoryRepository
import io.github.assistent.lists.ListRepository
import io.github.assistent.messages.MessageManager
import io.github.assistent.quiz.QuizRepository
import io.github.assistent.reminders.ReminderScheduler
import io.github.assistent.reminders.ReminderWizard
import io.github.assistent.sleep.SleepTracker
import io.github.assistent.timers.TimerManager
import io.github.assistent.tools.CancelReminderTool
import io.github.assistent.tools.CancelTimerTool
import io.github.assistent.tools.AddEventTool
import io.github.assistent.tools.CreateReminderTool
import io.github.assistent.tools.DeleteEventTool
import io.github.assistent.tools.DeviceTool
import io.github.assistent.tools.TvControlTool
import io.github.assistent.tools.FetchUrlTool
import io.github.assistent.tools.ForgetTool
import io.github.assistent.tools.ListRemindersTool
import io.github.assistent.tools.ListTimersTool
import io.github.assistent.tools.ListEventsTool
import io.github.assistent.tools.ListAddTool
import io.github.assistent.tools.ListCheckTool
import io.github.assistent.tools.ListRemoveTool
import io.github.assistent.tools.ListShowTool
import io.github.assistent.tools.DeleteMessageTool
import io.github.assistent.tools.LeaveMessageTool
import io.github.assistent.tools.RegisterFaceTool
import io.github.assistent.tools.SetPersonBirthdayTool
import io.github.assistent.tools.SetPersonObservationTool
import io.github.assistent.tools.ListMessagesTool
import io.github.assistent.tools.RecallTool
import io.github.assistent.tools.RememberTool
import io.github.assistent.tools.StartTimerTool
import io.github.assistent.tools.SpotifyPlayTool
import io.github.assistent.tools.SpotifyDevicesTool
import io.github.assistent.tools.SpotifyTransferTool
import io.github.assistent.tools.SleepReportTool
import io.github.assistent.tools.StartQuizTool
import io.github.assistent.tools.RecordQuizScoreTool
import io.github.assistent.tools.ToolRegistry
import io.github.assistent.brain.EmbeddingClient
import io.github.assistent.core.DailyBrief
import io.github.assistent.tools.DailyBriefTool
import io.github.assistent.tools.WeatherTool
import io.github.assistent.tools.WebSearchTool
import io.github.assistent.tools.WhatsNextTool
import io.github.assistent.voice.Listener
import io.github.assistent.voice.BargeInDetector
import io.github.assistent.voice.PcmPlayer
import io.github.assistent.voice.Speaker
import io.github.assistent.voice.VoiceIdentityManager
import io.github.assistent.voice.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AssistentApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.api.start()
    }
}

/** Manual dependency graph: everything is a process-wide singleton. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val emotionEngine = EmotionEngine(scope)
    val ambientBuffer = AmbientBuffer()
    val neuralBrain = NeuralBrainClient(emotionEngine, scope)
    val proactiveCache by lazy { ProactiveCache(memory, config, http, ambientBuffer, scope) }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val config = ConfigStore(context.filesDir)
    val db = AppDatabase.create(context)
    val memory = MemoryRepository(db.memories(), db.people())
    val presence = PresenceTracker(db.people(), scope)
    val reminders = ReminderScheduler(context, db.reminders())
    val reminderWizard = ReminderWizard(reminders, db.people())
    val timers = TimerManager(context, db.timers(), scope)
    val calendar = CalendarRepository(db.events())
    val messages = MessageManager(db.messages(), db.people(), scope)
    val lists = ListRepository(db.lists())
    val sleep = SleepTracker(db.sleep(), presence)
    val quizzes = QuizRepository(db.quizzes())
    val companionTokens = CompanionTokenManager(db.companionTokens())
    val voiceIdentity = VoiceIdentityManager(context, config, http, db.speakerProfiles(), db.people())
    val alerts = AlertManager(db.alerts())
    val events = EventDispatcher(config, http, scope)
    val houseMood = io.github.assistent.core.HouseMood(events)
    val chatgpt = io.github.assistent.brain.ChatGptAuth(context, http, scope)
    val llm = LlmClient(http).also { it.chatgpt = chatgpt }
    val diary = io.github.assistent.core.DiaryManager(db.diary(), config, llm, calendar, reminders, messages, sleep, alerts, events, scope)
    val dailyBrief = DailyBrief(calendar, reminders, timers, messages, lists, sleep, alerts)
    val embeddings = EmbeddingClient(config, http)
    val dreamImages = io.github.assistent.brain.DreamImageClient(config, http, java.io.File(context.filesDir, "dreams"))
    val phrases = PhrasePool(context.filesDir, config, llm, scope).also { Catchphrases.pool = it }
    val people = People(context, config, http, db.people(), scope).also { p -> scope.launch { p.migratePipeline() } }
    val registerFaceTool = RegisterFaceTool(people)
    /** TVs/PCs on the network: scan, Wake-on-LAN, ADB, DIAL, Bluetooth. */
    val home = io.github.assistent.tools.HomeDevices(context, config, http, io.github.assistent.tools.AdbRemote(context))
    val deviceTool = DeviceTool(context, config).also { it.home = home }
    val tvTool = TvControlTool(context, config, http, home)

    /** The owner's Spotify account (PKCE login, private tokens). */
    val spotify = io.github.assistent.tools.SpotifyAuth(context, config, http, scope)

    val tools = ToolRegistry(
        builtins = listOf(
            WebSearchTool(http, config),
            FetchUrlTool(http),
            WeatherTool(http, config),
            RememberTool(memory, presence),
            RecallTool(memory, presence),
            ForgetTool(memory),
            CreateReminderTool(reminders, presence),
            ListRemindersTool(reminders),
            CancelReminderTool(reminders),
            StartTimerTool(timers, presence),
            ListTimersTool(timers),
            CancelTimerTool(timers),
            AddEventTool(calendar, presence),
            ListEventsTool(calendar, presence),
            WhatsNextTool(calendar),
            DeleteEventTool(calendar),
            LeaveMessageTool(messages),
            ListMessagesTool(messages),
            DeleteMessageTool(messages),
            registerFaceTool,
            SetPersonObservationTool(people, registerFaceTool),
            SetPersonBirthdayTool(people),
            ListAddTool(lists, presence),
            ListShowTool(lists),
            ListCheckTool(lists),
            ListRemoveTool(lists),
            SpotifyPlayTool(context, spotify),
            io.github.assistent.tools.SpotifyControlTool(spotify),
            SpotifyDevicesTool(spotify),
            SpotifyTransferTool(spotify),
            SleepReportTool(sleep),

            RecordQuizScoreTool(quizzes, presence),
            deviceTool,
            tvTool,
        ),
        config = config,
        http = http,
    )

    val brain = Brain(config, llm, tools, memory, presence)
    val speaker = Speaker(context, config, http, PcmPlayer())
    val listener = Listener(context, config, http)
    val bargeIn = BargeInDetector()
    val wakeWord = WakeWordEngine(context, config, http)
    val runtime = AssistantRuntime(config, brain, speaker, listener, bargeIn, sleep, events, db.people(), scope, tools, http)
    val mcp = io.github.assistent.tools.McpManager(config, tools, http, scope)
    val live = io.github.assistent.live.LiveManager(config, brain, tools, runtime, events, http, scope)
    init {
        mcp.start()
        scope.launch {
            kotlinx.coroutines.delay(1_000)
            deviceTool.discoverNow()
        }
        alerts.runtime = runtime
        tools.register(io.github.assistent.tools.LookTool(runtime, config, llm))
        tools.register(io.github.assistent.tools.ReadDiaryTool(diary))
        tools.register(DailyBriefTool(dailyBrief, runtime))

        // Wire semantic memory embeddings.
        val embedDao = db.memoryEmbeddings()
        memory.embeddingDao = embedDao
        memory.queryEmbedder = { text -> embeddings.embed(listOf(text))?.firstOrNull() }
        memory.onEmbedQueue = { id, text ->
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val cfg = config.current
                if (cfg.memory.semantic == "off" || !embeddings.isAvailable()) return@launch
                val vec = embeddings.embed(listOf(text))?.firstOrNull() ?: return@launch
                embedDao.insert(io.github.assistent.data.MemoryEmbeddingEntity(
                    memoryId = id, dim = vec.size, model = embeddings.model(),
                    vector = EmbeddingClient.toBytes(vec),
                ))
            }
        }
        // Backfill embeddings for existing memories without vectors.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!embeddings.isAvailable()) return@launch
            val existing = embedDao.all().map { it.memoryId }.toSet()
            val all = memory.recent(2000)
            val missing = all.filter { it.id !in existing }
            for (m in missing) {
                kotlinx.coroutines.delay(2_000L)
                val vec = embeddings.embed(listOf(m.content))?.firstOrNull() ?: continue
                embedDao.insert(io.github.assistent.data.MemoryEmbeddingEntity(
                    memoryId = m.id, dim = vec.size, model = embeddings.model(),
                    vector = EmbeddingClient.toBytes(vec),
                ))
            }
        }

        runtime.life.onBriefing = {
            scope.launch {
                val weather = runtime.state.value.ambient.let { a ->
                    buildString { a.temperature?.let { t -> append("${t.toInt()}°C, ") }; append(a.condition) }.takeIf { it.isNotBlank() }
                }
                val b = dailyBrief.build(0, weather)
                val spoken = dailyBrief.toSpoken(b)
                when (config.current.life.briefing) {
                    "bubble" -> runtime.showBubble(spoken, speak = false, emotion = Emotion.HAPPY)
                    else -> runtime.say(spoken, Emotion.HAPPY)
                }
            }
        }
        runtime.emotionEngine = emotionEngine
        runtime.life.emotionEngine = emotionEngine
        runtime.life.ambientBuffer = ambientBuffer
        runtime.life.houseMood = houseMood
        runtime.life.diary = diary
        runtime.life.onAmbientComment = { ctx ->
            if (ctx.isNotBlank()) scope.launch {
                runtime.showBubble("Ouvi uma coisa aí...", speak = true, emotion = io.github.assistent.core.Emotion.THINKING)
                kotlinx.coroutines.delay(1_500)
                runtime.manualTrigger.trySend(Unit)
            }
        }
        brain.emotionEngine = emotionEngine
        brain.ambientBuffer = ambientBuffer
        brain.houseMood = houseMood
        runtime.houseMood = houseMood
        brain.coroutineScope = scope
        proactiveCache.calendarSource = {
            val events = calendar.upcoming(3)
            if (events.isEmpty()) null
            else {
                val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.forLanguageTag("pt-BR"))
                "Próximos compromissos: " + events.joinToString("; ") { "${it.title} em ${fmt.format(java.util.Date(it.startAt))}" }
            }
        }
        runtime.neuralBrain = neuralBrain
        runtime.life.onDreamBubble = dreamBubble@{ moodHint ->
            val raw = brain.dreamPhrase(moodHint) ?: return@dreamBubble null
            val cfg = config.current.life
            if (cfg.dreamImages == "off") return@dreamBubble raw
            // Parse JSON {"phrase":"...", "imagePrompt":"..."}
            val jsonStart = raw.indexOf('{'); val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) return@dreamBubble raw
            val json = runCatching { org.json.JSONObject(raw.substring(jsonStart, jsonEnd + 1)) }.getOrNull()
                ?: return@dreamBubble raw
            val phrase = json.optString("phrase", raw)
            val imagePrompt = json.optString("imagePrompt", "")
            if (imagePrompt.isNotBlank()) {
                val blocklist = runCatching { db.people().all().map { it.name } }.getOrElse { emptyList() }
                val safePrompt = io.github.assistent.brain.DreamImageClient.sanitize(imagePrompt, blocklist)
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val jpeg = runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(25_000) { dreamImages.fetch(safePrompt) }
                    }.getOrNull() ?: return@launch
                    if (jpeg != null) runtime.showFaceImage(jpeg)
                }
            }
            phrase
        }
        // Save significant ambient phrases to long-term memory for future recall.
        ambientBuffer.onSignificant = { text ->
            scope.launch { memory.add(text, tags = "ambient ouvido", source = "ambient", share = false) }
        }
        proactiveCache.start()
        config.current.llm.neuralServerUrl.takeIf { it.isNotBlank() }?.let { neuralBrain.configure(it) }
        scope.launch {
            config.config.collect { cfg ->
                val url = cfg.llm.neuralServerUrl
                if (url != neuralBrain.serverUrl) neuralBrain.configure(url)
            }
        }
        runtime.reminderPhrases = io.github.assistent.core.ReminderPhrases(config, llm)
        runtime.nameBargeIn = { stops, speakingNow -> wakeWord.awaitBargeIn(stops, speakingNow) }
        reminders.onEscalate = runtime::onReminderEscalation
        messages.onDeliver = { person, message ->
            runtime.life.play(io.github.assistent.face.Scene.MESSAGE_DELIVERY)
            runtime.showBubble("${person.name}, recado: ${message.text}", speak = true, emotion = io.github.assistent.core.Emotion.HAPPY)
        }
        // "Turn the TV off in 30 minutes" = a named timer that switches it off when it ends (survives restarts).
        tvTool.sleepTimer = { name, minutes ->
            timers.create("Desligar $name", minutes * 60_000L)
            "$name desliga em $minutes min"
        }
        tvTool.cancelSleepTimer = { name ->
            val list = timers.list().filter { it.label == "Desligar $name" }
            list.forEach { timers.cancel(it.id) }
            if (list.isEmpty()) "não havia timer para desligar $name" else "timer de desligar $name cancelado"
        }
        val tvTimerDone: (io.github.assistent.data.TimerEntity) -> Boolean = { timer ->
            if (timer.label.startsWith("Desligar ")) {
                scope.launch {
                    val result = tvTool.execute(kotlinx.serialization.json.buildJsonObject {
                        put("action", kotlinx.serialization.json.JsonPrimitive("power_off"))
                        put("device", kotlinx.serialization.json.JsonPrimitive(timer.label.removePrefix("Desligar ")))
                    })
                    events.emit("tv_sleep_timer", kotlinx.serialization.json.JsonPrimitive(result))
                }
                true
            } else false
        }
        timers.onDone = onDone@{ timer ->
            if (tvTimerDone(timer)) return@onDone
            runtime.wake(greet = false)
            runtime.life.play(io.github.assistent.face.Scene.TIMER_DONE)
            val text = "O ${timer.label} ta pronto!"
            val sentRemote = runtime.mesh?.let { mesh ->
                val person = timer.personId?.let { db.people().get(it) }
                val room = person?.name?.let { mesh.whereIs(it, withinMs = 15 * 60_000)?.room } ?: timer.room
                if (room.isNotBlank() && !room.equals(config.current.device.room, ignoreCase = true)) {
                    mesh.sayInRoom(room, text)
                } else {
                    false
                }
            } ?: false
            if (!sentRemote) runtime.say(text, io.github.assistent.core.Emotion.SURPRISED)
        }
    }
    val greeter = Greeter(config, runtime, people, db.reminders(), llm, events, scope)
    val mesh = Mesh(config, http, db.people(), memory, scope).also { m ->
        memory.onAdded = m::shareMemory
        people.onEnrolled = m::sharePerson
        greeter.onKnownPersonSeen = { person ->
            presence.onSeen(person)
            scope.launch { messages.deliverFor(person) }
            scope.launch {
                val pending = reminders.listPersonTriggered(person.id)
                for (r in pending) {
                    runtime.wake(greet = false)
                    runtime.say(runtime.reminderLine(r, person.name), io.github.assistent.core.Emotion.HAPPY)
                    reminders.firePersonTriggered(r)
                }
            }
        }
        // Presence is cleared by FaceTracker when no face remains in the frame. A failed
        // embedding while the face is still visible must not erase the current person.
        // An unknown face must never inherit the previous person's context.
        // Short recognition glitches are handled by Greeter's identity lock
        // before this callback is reached.
        greeter.onNoKnownPersonSeen = { presence.clear() }
        greeter.onKnownSeen = m::reportPresence
        m.onPresenceSeen = { presence.onSeenByName(it.person, it.at) }
        runtime.mesh = m
        m.onRemoteSay = { text -> scope.launch { runtime.say(text) } }
        tools.register(WhereIsTool(m))
        tools.register(SayInRoomTool(m))
        tools.register(ListRoomsTool(m))
        tools.register(BroadcastTool(m))
        tools.register(CallRoomTool(m))
        m.start()
    }
    val ears = io.github.assistent.voice.SoundEars(context, config, http, scope)
    val interactions = Interactions(context, config, runtime, scope).also { i ->
        i.start()
        ears.start()
        runtime.isMusicPlaying = { i.musicPlaying }
        val previous = greeter.onKnownSeen
        greeter.onKnownSeen = { name -> previous(name); i.onKnownSeen(name) }
        i.onInteraction = { kind -> if (config.current.life.houseMood) houseMood.onInteraction(kind) }
        i.onSpeechInRoom = {
            scope.launch {
                val pending = reminders.listPersonTriggered()
                if (pending.isNotEmpty()) {
                    val name = pending.first().style?.takeIf { it.isNotBlank() } ?: return@launch
                    runtime.showBubble("Ei, $name… vem cá!", speak = true, emotion = io.github.assistent.core.Emotion.HAPPY)
                }
            }
        }
    }
    /** Hands on camera: waves and gestures (MediaPipe). */
    val hands = io.github.assistent.vision.HandWatcher(context, config, http, scope)

    /** Voice games run by the robot (quiz, guess the number, rock-paper-scissors with the hand, true or false). */
    val games = io.github.assistent.games.GameMaster(llm, config, quizzes, presence) { hands.lastSeen }.also {
        runtime.games = it
        tools.register(io.github.assistent.tools.StartGameTool(it))
    }

    /** Bodies, objects and outfits of the day on camera. */
    val body = io.github.assistent.vision.BodyWatcher(context, config, http, scope)
    /** Who is around the house from voice and clothes (weak evidence, never the trusted identity). */
    val roomPresence = io.github.assistent.core.RoomPresence(
        config, voiceIdentity, db.people(), presence, events, scope,
        recentAudio = { s -> wakeWord.snapshot(s) },
        musicPlaying = { interactions.musicPlaying },
    ).also { rp ->
        rp.report = mesh::reportPresence
        body.onOutfitMatch = rp::onOutfit
        body.onObjects = interactions::onObjectsSeen
        hands.onWave = interactions::onHandWave
        hands.onGesture = interactions::onHandGesture
        ears.onSound = { kind, score ->
            if (config.current.life.houseMood) houseMood.onSound(kind, score)
            interactions.onSound(kind, score)
            if (kind == "speech") rp.onSpeechHeard()
        }
        val known = greeter.onKnownPersonSeen
        greeter.onKnownPersonSeen = { p -> known(p); body.onFaceRecognized(p) }
        val unknown = greeter.onNoKnownPersonSeen
        greeter.onNoKnownPersonSeen = { unknown(); body.onFaceGone() }
    }
    /** Every tool call as an observable, cancellable operation (HUD, SSE, API). */
    val operations = io.github.assistent.core.Operations(events, scope).also {
        tools.operations = it
        runtime.cancelOperations = { it.cancelAll() }
    }
    val updater = io.github.assistent.update.Updater(context, config, http, scope).also { it.start() }
    val api = LocalApiServer(this)
}
