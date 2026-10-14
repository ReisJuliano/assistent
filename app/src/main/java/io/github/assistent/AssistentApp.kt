package io.github.assistent

import android.app.Application
import android.content.Context
import io.github.assistent.api.LocalApiServer
import io.github.assistent.alerts.AlertManager
import io.github.assistent.brain.Brain
import io.github.assistent.brain.LlmClient
import io.github.assistent.calendar.CalendarRepository
import io.github.assistent.companion.CompanionTokenManager
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.AssistantRuntime
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
import io.github.assistent.sleep.SleepTracker
import io.github.assistent.timers.TimerManager
import io.github.assistent.tools.CancelReminderTool
import io.github.assistent.tools.CancelTimerTool
import io.github.assistent.tools.AddEventTool
import io.github.assistent.tools.CreateReminderTool
import io.github.assistent.tools.DeleteEventTool
import io.github.assistent.tools.DeviceTool
import io.github.assistent.tools.FetchUrlTool
import io.github.assistent.tools.ForgetTool
import io.github.assistent.tools.ListRemindersTool
import io.github.assistent.tools.ListTimersTool
import io.github.assistent.tools.ListEventsTool
import io.github.assistent.tools.ListAddTool
import io.github.assistent.tools.ListCheckTool
import io.github.assistent.tools.ListRemoveTool
import io.github.assistent.tools.ListShowTool
import io.github.assistent.tools.LeaveMessageTool
import io.github.assistent.tools.RegisterFaceTool
import io.github.assistent.tools.SetPersonObservationTool
import io.github.assistent.tools.ListMessagesTool
import io.github.assistent.tools.RecallTool
import io.github.assistent.tools.RememberTool
import io.github.assistent.tools.StartTimerTool
import io.github.assistent.tools.SpotifyPlayTool
import io.github.assistent.tools.SpotifySimpleTool
import io.github.assistent.tools.SpotifyDevicesTool
import io.github.assistent.tools.SpotifyTransferTool
import io.github.assistent.tools.SleepReportTool
import io.github.assistent.tools.StartQuizTool
import io.github.assistent.tools.RecordQuizScoreTool
import io.github.assistent.tools.ToolRegistry
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
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val config = ConfigStore(context.filesDir)
    val db = AppDatabase.create(context)
    val memory = MemoryRepository(db.memories(), db.people())
    val presence = PresenceTracker(db.people(), scope)
    val reminders = ReminderScheduler(context, db.reminders())
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
    val chatgpt = io.github.assistent.brain.ChatGptAuth(context, http, scope)
    val llm = LlmClient(http).also { it.chatgpt = chatgpt }
    val phrases = PhrasePool(context.filesDir, config, llm, scope).also { Catchphrases.pool = it }
    val people = People(context, config, http, db.people(), scope).also { p -> scope.launch { p.migratePipeline() } }
    val registerFaceTool = RegisterFaceTool(people)

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
            registerFaceTool,
            SetPersonObservationTool(people, registerFaceTool),
            ListAddTool(lists, presence),
            ListShowTool(lists),
            ListCheckTool(lists),
            ListRemoveTool(lists),
            SpotifyPlayTool(http, config),
            SpotifyDevicesTool(http, config),
            SpotifyTransferTool(http, config),
            SpotifySimpleTool("music_pause", "Pause Spotify playback.", "PUT", "me/player/pause", http, config),
            SpotifySimpleTool("music_next", "Skip to next Spotify track.", "POST", "me/player/next", http, config),
            SpotifySimpleTool("music_volume", "Set Spotify playback volume.", "PUT", "me/player/volume", http, config, hasVolume = true),
            SpotifySimpleTool("music_now_playing", "Get the current Spotify track.", "GET", "me/player/currently-playing", http, config),
            SleepReportTool(sleep),
            StartQuizTool(llm, config),
            RecordQuizScoreTool(quizzes, presence),
            DeviceTool(context, config),
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
    init {
        alerts.runtime = runtime
        runtime.reminderPhrases = io.github.assistent.core.ReminderPhrases(config, llm)
        reminders.onEscalate = runtime::onReminderEscalation
        messages.onDeliver = { person, message ->
            runtime.life.play(io.github.assistent.face.Scene.MESSAGE_DELIVERY)
            runtime.showBubble("${person.name}, recado: ${message.text}", speak = true, emotion = io.github.assistent.core.Emotion.HAPPY)
        }
        timers.onDone = { timer ->
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
        ears.onSound = i::onSound
        ears.start()
        runtime.isMusicPlaying = { i.musicPlaying }
        val previous = greeter.onKnownSeen
        greeter.onKnownSeen = { name -> previous(name); i.onKnownSeen(name) }
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
        ears.onSound = { kind, score ->
            interactions.onSound(kind, score)
            if (kind == "speech") rp.onSpeechHeard()
        }
        val known = greeter.onKnownPersonSeen
        greeter.onKnownPersonSeen = { p -> known(p); body.onFaceRecognized(p) }
        val unknown = greeter.onNoKnownPersonSeen
        greeter.onNoKnownPersonSeen = { unknown(); body.onFaceGone() }
    }
    val updater = io.github.assistent.update.Updater(context, config, http, scope).also { it.start() }
    val api = LocalApiServer(this)
}
