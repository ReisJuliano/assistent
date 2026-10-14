package io.github.assistent.core

import android.util.Log
import io.github.assistent.brain.Brain
import io.github.assistent.brain.Reply
import io.github.assistent.config.ConfigStore
import io.github.assistent.data.PersonDao
import io.github.assistent.data.ReminderEntity
import io.github.assistent.tools.Tool
import io.github.assistent.tools.ToolRegistry
import io.github.assistent.tools.num
import io.github.assistent.tools.params
import io.github.assistent.tools.str
import io.github.assistent.voice.Listener
import io.github.assistent.voice.Speaker
import io.github.assistent.voice.BargeInDetector
import io.github.assistent.voice.SpeechPipeline
import io.github.assistent.sleep.SleepTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The assistant's "body": owns what the face shows and glues brain, voice and events together.
 * Used by the UI, the voice service and the local API alike.
 */
class AssistantRuntime(
    private val config: ConfigStore,
    private val brain: Brain,
    private val speaker: Speaker,
    private val listener: Listener,
    private val bargeIn: BargeInDetector,
    private val sleepTracker: SleepTracker,
    private val events: EventDispatcher,
    private val people: PersonDao,
    private val scope: CoroutineScope,
    tools: ToolRegistry,
    http: okhttp3.OkHttpClient,
) {
    val state = MutableStateFlow(AssistantState())
    /** Hobbies, dreams, wake-up reactions and weather ambience. */
    val life = LifeDirector(config, state, http, events, scope)
    val gaze = MutableStateFlow(Gaze())
    /** Tap on the face / API "listen": asks the voice loop to start listening now. */
    val manualTrigger = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var micLevel = 0f
    @Volatile private var lastInteraction = System.currentTimeMillis()
    @Volatile private var sleepAfterReply = false
    @Volatile private var interruptRequested = false
    @Volatile var cameraBrightness: Float = 255f
    private val spokenReply = StringBuilder()
    private val generatedReply = StringBuilder()
    private var currentReplyText = ""
    private var currentReplyEmotion = Emotion.NEUTRAL
    private var emotionJob: Job? = null
    private var bargeInJob: Job? = null
    private var activeChatJob: Job? = null
    /** Set by the app graph: listens for the wake phrase / stop words while speaking ("name" barge-in mode). */
    var nameBargeIn: (suspend (List<String>, () -> String) -> String?)? = null
    @Volatile private var bargeStopOnly = false
    private var speechPipeline: SpeechPipeline? = null
    @Volatile private var interruptedThisTurn = false
    @Volatile private var pendingInterruptText = ""
    private var waitingLineJob: Job? = null
    private var waitingLineActivity: Activity? = null
    @Volatile private var waitingLineStarted = false

    init {
        tools.register(SleepTool())
        tools.register(LightTool())
        life.onSelfWake = {
            scope.launch {
                wake(greet = true, interaction = false)
                delay(3_500)
                if (state.value.activity == Activity.IDLE) showBubble(Catchphrases.line("anyone_there", LifeDirector.anyoneThereLines()))
                dozeOffIfAlone(2 * 60_000L + kotlin.random.Random.nextLong(2 * 60_000))
            }
        }
        life.onQuip = { text, sleeping -> showBubble(text, emotion = if (sleeping) Emotion.SLEEPY else Emotion.HAPPY, keepEmotion = sleeping) }
        life.start()
        scope.launch {
            while (true) {
                delay(5_000)
                val after = config.current.wake.sleepAfterSeconds
                val s = state.value
                val limit = after * 1000L
                if (after > 0 && s.activity == Activity.IDLE && s.scene == null && System.currentTimeMillis() - lastInteraction > limit) sleep()
            }
        }
    }

    /** Loudness that drives the face: speech output while speaking, mic while listening. */
    fun audioLevel(): Float = when (state.value.activity) {
        Activity.SPEAKING -> speaker.player.level
        Activity.LISTENING -> micLevel
        else -> 0f
    }

    fun touch() { lastInteraction = System.currentTimeMillis() }

    /** Lights the face for the camera when it is too dark to recognise someone. */
    fun requestLightAssist() {
        lightOn("random", 12)
    }

    private var lightJob: Job? = null

    /**
     * Turns the screen into a light (white panel at full brightness) with a gag: desk lamp, phone flashlight or a
     * bright-idea bulb ([style] lamp/phone/bulb/random). Stays on for [seconds] or until [lightOff].
     */
    fun lightOn(style: String = "random", seconds: Int = 300): String {
        val current = io.github.assistent.face.Scene.of(state.value.scene)
        val lit = io.github.assistent.face.LightGag.isLight(current) && state.value.lightOffAt == 0L
        if (!lit) {
            if (state.value.activity == Activity.SLEEPING) wake(greet = false)
            state.update { it.copy(lightOffAt = 0L) }
            life.play(io.github.assistent.face.LightGag.sceneFor(style))
        }
        lightJob?.cancel()
        val secs = seconds.coerceIn(5, 3600)
        lightJob = scope.launch { delay(secs * 1000L); lightOff() }
        return if (lit) "light already on, kept for $secs s" else "light on for $secs s"
    }

    /** Switches the light off with the gag's own gesture (pulls the chain again, taps the phone...). */
    fun lightOff(): String {
        val s = state.value
        val sc = io.github.assistent.face.Scene.of(s.scene)
        if (!io.github.assistent.face.LightGag.isLight(sc) || sc == null || s.lightOffAt != 0L) return "light is off"
        lightJob?.cancel()
        // Never switch off before the gag has even turned it on.
        val earliest = s.sceneStartedAt + (io.github.assistent.face.LightGag.onAt(sc) * 1000).toLong() + 600
        lightJob = scope.launch {
            delay((earliest - System.currentTimeMillis()).coerceAtLeast(0))
            state.update { it.copy(lightOffAt = System.currentTimeMillis()) }
            delay((io.github.assistent.face.LightGag.endAfterOff(sc) * 1000).toLong())
            if (state.value.scene == sc.key) life.clear()
            state.update { it.copy(lightOffAt = 0L) }
        }
        return "light off"
    }

    fun setActivity(a: Activity) {
        val previous = state.value.activity
        state.update { it.copy(activity = a) }
        if (a != Activity.THINKING && a != Activity.SEARCHING) {
            // A filler that has not started yet is disposable. Once TTS has
            // started, cancelling its job cuts the sentence after a few words.
            if (!waitingLineStarted) {
                waitingLineJob?.cancel()
                waitingLineJob = null
                waitingLineActivity = null
            }
            return
        }
        if (a == previous && waitingLineJob?.isActive == true && !waitingLineStarted) return
        if (!waitingLineStarted) waitingLineJob?.cancel()
        waitingLineActivity = a
        waitingLineJob = scope.launch {
            try {
                delay(if (a == Activity.SEARCHING) 1_300L else 1_700L)
                if (state.value.activity != a || waitingLineActivity != a) return@launch
                val line = Catchphrases.line("thinking_wait", WAITING_LINES)
                showBubble(line, speak = false, emotion = Emotion.THINKING, keepEmotion = true)
                // Keep this coroutine alive until the complete filler is
                // spoken. The response's speaker.speak() will wait on the
                // Speaker mutex instead of interrupting it.
                waitingLineStarted = true
                speaker.speak(line)
            } finally {
                waitingLineStarted = false
                if (waitingLineActivity == a) {
                    waitingLineJob = null
                    waitingLineActivity = null
                }
            }
        }
    }

    fun setEmotion(e: Emotion, holdMs: Long = 9_000) {
        emotionJob?.cancel()
        state.update { it.copy(emotion = e) }
        events.emit("emotion", JsonPrimitive(e.tag))
        if (holdMs > 0) emotionJob = scope.launch {
            delay(holdMs)
            state.update { it.copy(emotion = Emotion.NEUTRAL) }
        }
    }

    fun sleep() {
        if (state.value.activity == Activity.SLEEPING) return
        interruptSpeaking()
        setActivity(Activity.SLEEPING)
        scope.launch { sleepTracker.onSleep() }
        events.emit("sleep")
    }

    fun interruptSpeaking() {
        interruptRequested = true
        speechPipeline?.cancel()
        speaker.stop()
        activeChatJob?.cancel()
        if (state.value.activity == Activity.SPEAKING) setEmotion(Emotion.CONFUSED, 1_500)
    }

    private fun startBargeIn() {
        bargeInJob?.cancel()
        val mode = config.current.voice.bargeIn.lowercase()
        if (mode == "off") return
        bargeInJob = scope.launch {
            if (mode == "name") {
                val listenFor = nameBargeIn ?: return@launch
                val kind = listenFor(config.current.voice.bargeInStopWords) { speechPipeline?.speakingNow().orEmpty() } ?: return@launch
                bargeStopOnly = kind == "stop"
                pendingInterruptText = ""
                interruptSpeaking()
                setActivity(if (bargeStopOnly) Activity.IDLE else Activity.LISTENING)
            } else if (bargeIn.awaitSpeech { speaker.player.level }) {
                bargeStopOnly = false
                interruptSpeaking()
                setActivity(Activity.LISTENING)
            }
        }
    }

    private fun stopBargeIn() {
        bargeInJob?.cancel()
        bargeInJob = null
    }

    fun wake(greet: Boolean = true, interaction: Boolean = true) {
        if (interaction) touch()
        if (state.value.activity == Activity.SLEEPING) {
            setActivity(Activity.IDLE)
            scope.launch { sleepTracker.onWake() }
            life.onConversation() // just woke up: no hobby right away
            if (greet) {
                if (config.current.life.enabled && config.current.life.wakeReactions) life.onWokeUp()
                else setEmotion(Emotion.HAPPY, 2_500)
            }
            events.emit("wake")
        }
    }

    /** Immediate visual acknowledgement for a wake phrase, before the follow-up command is transcribed. */
    fun onWakePhraseHeard() {
        wake(greet = false)
        life.play(io.github.assistent.face.Scene.HEARD)
        setEmotion(Emotion.SURPRISED, 1_000)
    }

    private fun isIdentityQuestion(text: String): Boolean {
        val q = text.lowercase()
        return q.contains("quem") || q.contains("qual pessoa") || q.contains("qual e a pessoa") ||
            q.contains("reconhece") || q.contains("ta vendo") || q.contains("está vendo") ||
            q.contains("esta vendo") || q.contains("rosto")
    }

    suspend fun chat(text: String, speak: Boolean = true): Reply {
        wake(greet = false)
        life.onConversation()
        state.update { it.copy(lastHeard = text, partial = "", error = null) }
        events.emit("user_said", JsonPrimitive(text))
        if (speak && cameraBrightness < 28f && isIdentityQuestion(text)) {
            val line = Catchphrases.line("low_light_identity", LOW_LIGHT_LINES)
            showBubble(line, speak = false, emotion = Emotion.CONFUSED)
            say(line, Emotion.CONFUSED)
            requestLightAssist()
        }
        var spokeDuringStreaming = false
        interruptRequested = false
        interruptedThisTurn = false
        spokenReply.clear()
        generatedReply.clear()
        currentReplyText = ""
        currentReplyEmotion = Emotion.NEUTRAL
        val reply = try {
            if (speak) {
                val splitter = SentenceSplitter()
                val chatStartedAt = System.currentTimeMillis()
                var firstTokenAt = 0L
                var startedSpeaking = false
                val pipeline = SpeechPipeline(
                    scope = scope,
                    synthesize = speaker::synthesize,
                    play = speaker::playClip,
                    playedFraction = { speaker.player.playedFraction },
                    stopPlayback = speaker::stop,
                    prefetch = config.current.tts.prefetch,
                    onFirstChunk = {
                        Log.i(TAG, "first audio after ${System.currentTimeMillis() - chatStartedAt}ms (first token ${firstTokenAt - chatStartedAt}ms)")
                        if (!startedSpeaking) {
                            startedSpeaking = true
                            spokeDuringStreaming = true
                            setActivity(Activity.SPEAKING)
                            startBargeIn()
                        }
                    },
                    onHeard = { chunk -> spokenReply.append(chunk).append(' ') },
                    onGap = { ms -> Log.d(TAG, "speech gap ${ms}ms") },
                )
                speechPipeline = pipeline
                pipeline.start()
                try {
                    val generation = scope.async {
                        brain.askStreaming(text, { if (!startedSpeaking) setActivity(it) }) { delta ->
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            generatedReply.append(delta)
                            for (sentence in splitter.push(delta)) {
                                if (interruptRequested) return@askStreaming
                                pipeline.submit(sentence)
                            }
                        }
                    }
                    activeChatJob = generation
                    generation.await().also {
                        currentReplyText = it.text
                        currentReplyEmotion = it.emotion
                        val tail = splitter.flush()
                        if (tail.isNotBlank() && !interruptRequested) pipeline.submit(tail)
                        pipeline.finish()
                        // Generation was already complete but the speech was cut: remember where the listener stopped.
                        if (interruptRequested) {
                            brain.noteInterrupted(it.text, pipeline.cancel(), it.emotion)
                            interruptedThisTurn = true
                        }
                    }
                } catch (e: CancellationException) {
                    val heard = pipeline.cancel()
                    if (interruptRequested) {
                        brain.noteInterrupted(generatedReply.toString(), heard.ifBlank { spokenReply.toString() }, currentReplyEmotion)
                        interruptedThisTurn = true
                        activeChatJob = null
                        setActivity(Activity.LISTENING)
                        return Reply("", currentReplyEmotion, emptyList())
                    }
                    throw e
                } finally {
                    if (speechPipeline === pipeline) speechPipeline = null
                }
            } else {
                brain.ask(text) { setActivity(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: io.github.assistent.brain.LlmLimitException) {
            val at = java.time.Instant.ofEpochMilli(e.untilMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            state.update { it.copy(error = "AI usage limit until $at") }
            Reply("Meu cérebro tá sem energia agora: acabou o limite da conta de IA. Volto a pensar umas ${"%02d:%02d".format(at.hour, at.minute)}.", Emotion.SAD, emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "brain failed", e)
            state.update { it.copy(error = e.message) }
            val chatgpt = config.current.llm.provider == "chatgpt"
            Reply(if (chatgpt) CHATGPT_ERROR else FALLBACK_ERROR, Emotion.SAD, emptyList())
        }
        currentReplyText = reply.text
        currentReplyEmotion = reply.emotion
        state.update { it.copy(lastReply = reply.text) }
        setEmotion(reply.emotion)
        events.emit("reply", buildJsonObject {
            put("text", reply.text); put("emotion", reply.emotion.tag)
            put("tools", kotlinx.serialization.json.JsonArray(reply.toolsUsed.map(::JsonPrimitive)))
        })
        if (speak && !spokeDuringStreaming && !interruptRequested) say(reply.text) else setActivity(Activity.IDLE)
        scope.launch { brain.autoRemember(text, reply.text) }
        stopBargeIn()
        activeChatJob = null
        if (sleepAfterReply) { sleepAfterReply = false; sleep() }
        return reply
    }

    suspend fun say(text: String, emotion: Emotion? = null, spontaneous: Boolean = false) {
        wake(greet = false, interaction = !spontaneous)
        if (!spontaneous) life.onConversation()
        emotion?.let { setEmotion(it) }
        setActivity(Activity.SPEAKING)
        startBargeIn()
        try {
            speaker.speak(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tts failed", e)
            state.update { it.copy(error = "TTS: ${e.message}") }
        } finally {
            stopBargeIn()
            setActivity(Activity.IDLE)
            if (!spontaneous) touch()
        }
    }

    suspend fun listen(noSpeechTimeoutMs: Long): String {
        life.onConversation()
        setActivity(Activity.LISTENING)
        return try {
            listener.listen(noSpeechTimeoutMs, { micLevel = it }, { p -> state.update { it.copy(partial = p) } }).trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "stt failed", e)
            state.update { it.copy(error = "STT: ${e.message}") }
            ""
        } finally {
            micLevel = 0f
            if (state.value.activity == Activity.LISTENING) setActivity(Activity.IDLE)
            touch()
        }
    }

    /** A full voice exchange: listen (unless the command came with the wake phrase), answer, follow-ups. */
    suspend fun converse(initial: String = "") {
        wake(greet = false)
        var text = initial.trim().ifBlank { listen(7_000) }
        if (text.isBlank()) {
            setEmotion(Emotion.CONFUSED, 2_000)
            if (state.value.activity == Activity.LISTENING) setActivity(Activity.IDLE)
            return
        }
        if (isGoodNight(text)) {
            sleep()
            return
        }
        if (isStop(text)) {
            stopEverything()
            return
        }
        // A game is on: the answer goes to the game, not to the LLM.
        if (games?.active == true) { playGame(firstAnswer = text); return }
        while (text.isNotBlank()) {
            chat(text, speak = true)
            // start_game was called: the robot takes over right after its short reply.
            if (games?.pending != null) { playGame(); return }
            if (interruptedThisTurn) {
                interruptedThisTurn = false
                // "para", "chega"…: just be quiet, don't start listening.
                if (bargeStopOnly) { bargeStopOnly = false; break }
                if (state.value.activity == Activity.SLEEPING) break
                val interrupted = pendingInterruptText.also { pendingInterruptText = "" }
                text = interrupted.ifBlank { listen(7_000) }
                if (text.isBlank()) break
                if (isGoodNight(text)) { sleep(); return }
                if (isStop(text)) { stopEverything(); return }
                continue
            }
            val follow = config.current.wake.followUpSeconds
            // With music on, the song would be taken as the next question: wait for the wake phrase instead.
            if (follow <= 0 || state.value.activity == Activity.SLEEPING || isMusicPlaying()) break
            text = listen(follow * 1000L)
            if (isGoodNight(text)) { sleep(); return }
            if (isStop(text)) { stopEverything(); return }
        }
    }

    private fun isGoodNight(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^a-záéíóúãõç ]"), " ").replace(Regex("\\s+"), " ").trim()
        return t.contains("boa noite") || t.contains("vai dormir") || t == "durma"
    }

    private fun isStop(text: String): Boolean {
        val t = text.lowercase().replace(Regex("[^a-záéíóúãõç ]"), " ").replace(Regex("\\s+"), " ").trim()
        return t == "parar" || t == "pare" || t == "para tudo" || t == "pare tudo" || t == "silêncio" || t == "silencio"
    }

    /** Set by the app graph: cancels every running tool operation. */
    var cancelOperations: () -> Unit = {}

    /** Immediate hard stop for speech, bubbles, life scenes and running operations. */
    fun stopEverything() {
        cancelOperations()
        interruptRequested = true
        speechPipeline?.cancel()
        activeChatJob?.cancel()
        speaker.stop()
        stopBargeIn()
        bubbleJob?.cancel()
        state.update { it.copy(activity = Activity.IDLE, bubble = null, partial = "") }
        life.clear()
        touch()
    }

    fun onReminder(r: ReminderEntity) {
        events.emit("reminder", buildJsonObject { put("id", r.id); put("message", r.message); put("kind", r.kind); put("type", r.type) })
        scope.launch {
            val owner = r.personId?.let { people.get(it)?.name } ?: config.current.personality.userName
            val text = reminderLine(r, owner)
            val here = config.current.device.room
            val remote = mesh?.whereIs(owner, withinMs = 10 * 60_000)
            val sentRemote = remote?.takeIf { owner.isNotBlank() && !it.room.equals(here, true) }?.let {
                mesh?.sayInRoom(it.room, text) == true
            } == true
            if (!sentRemote) {
                wake(greet = false)
                reminderScene(r)?.let { life.play(it) }
                say(text, if (r.kind == "alarm") Emotion.SURPRISED else Emotion.HAPPY)
            }
        }
    }

    suspend fun onReminderEscalation(r: ReminderEntity) {
        val target = r.escalateToPersonId?.let { people.get(it)?.name }
        val owner = r.personId?.let { people.get(it)?.name } ?: "alguem"
        val text = "O lembrete de $owner ainda nao foi confirmado: ${r.message}"
        val remote = target?.let { mesh?.whereIs(it, withinMs = 15 * 60_000) }
        if (remote != null && !remote.room.equals(config.current.device.room, true)) {
            mesh?.sayInRoom(remote.room, text)
        } else {
            showBubble(text, speak = true, emotion = Emotion.SURPRISED)
        }
    }

    /** Set by the app graph: music is playing in the room (no hands-free follow-up then). */
    var isMusicPlaying: () -> Boolean = { false }

    /** Set by the app graph: voice games (the robot runs them itself while one is on). */
    var games: io.github.assistent.games.GameMaster? = null

    /**
     * Plays the pending/active game until it ends: the robot speaks each turn (options in the bubble) and listens for
     * the answer without needing the wake phrase. Twice silent → game over.
     */
    suspend fun playGame(firstAnswer: String? = null) {
        val gm = games ?: return
        gm.pending?.let { (game, topic) -> speakTurn(gm.begin(game, topic)) }
        var answer = firstAnswer
        var silent = 0
        while (gm.active && state.value.activity != Activity.SLEEPING) {
            gm.prompt { say(it, Emotion.HAPPY) }
            val text = answer ?: listen(10_000)
            answer = null
            if (text.isBlank()) {
                if (++silent >= 2) { speakTurn(gm.timeout()); break }
                say("Tô esperando sua resposta!", Emotion.THINKING)
                continue
            }
            silent = 0
            val turn = gm.handle(text) ?: break
            speakTurn(turn)
        }
        if (state.value.activity == Activity.LISTENING) setActivity(Activity.IDLE)
    }

    private suspend fun speakTurn(t: io.github.assistent.games.GameTurn) {
        if (t.show.isNotBlank()) showBubble(t.show, speak = false, emotion = t.emotion)
        else setEmotion(t.emotion, 3_000)
        say(t.say, t.emotion)
    }

    /** Set by the app graph: personalised reminder lines. */
    var reminderPhrases: ReminderPhrases? = null

    /** The spoken line for a reminder; nags (unconfirmed repeats) get more insistent. */
    suspend fun reminderLine(r: ReminderEntity, owner: String): String {
        val nag = r.firstFiredAt?.let { first ->
            val every = (if (r.nagEveryMinutes > 0) r.nagEveryMinutes else 5) * 60_000L
            ((System.currentTimeMillis() - first) / every).toInt().coerceAtLeast(1)
        } ?: 0
        return reminderPhrases?.line(r, owner, nag) ?: r.message
    }

    private fun reminderScene(r: ReminderEntity): io.github.assistent.face.Scene? {
        io.github.assistent.face.Scene.of(r.animation)?.let { return it }
        return when (r.type) {
            "medicine" -> io.github.assistent.face.Scene.TAKE_PILL
            "water" -> io.github.assistent.face.Scene.DRINK_WATER
            "food" -> io.github.assistent.face.Scene.EAT_FOOD
            "exercise" -> io.github.assistent.face.Scene.STRETCH_BREAK
            else -> null
        }
    }

    private var bubbleJob: Job? = null

    /** Set by the app graph when multi-device is available. */
    var mesh: Mesh? = null

    /** Shows a speech bubble next to the face for a few seconds, optionally saying it too. */
    fun showBubble(text: String, speak: Boolean = false, emotion: Emotion = Emotion.HAPPY, keepEmotion: Boolean = false) {
        bubbleJob?.cancel()
        state.update { it.copy(bubble = text, bubbleAt = System.currentTimeMillis()) }
        if (!keepEmotion) setEmotion(emotion, 4_000)
        bubbleJob = scope.launch {
            if (speak) say(text, spontaneous = true)
            delay(4_000L + text.length * 55L)
            state.update { it.copy(bubble = null) }
        }
    }

    @Volatile private var lastMotionReaction = 0L

    /**
     * Movement seen by the camera while sleeping. Most of the time it just wakes up quietly; sometimes it gets
     * startled, sometimes it only peeks and goes back to sleep. It only *says* something if a real face shows up
     * in front of the camera a few seconds later — never to an empty room. If nobody interacts, it dozes off again.
     */
    fun onMotion(force: Boolean = false) {
        val life = config.current.life
        if (!force && (!life.enabled || !life.wakeOnMotion)) return
        if (state.value.activity != Activity.SLEEPING) return
        val now = System.currentTimeMillis()
        if (!force && now - lastMotionReaction < 45_000) { Log.d(TAG, "motion ignored (cooldown)"); return }
        Log.d(TAG, "motion while sleeping -> look for a face")
        lastMotionReaction = now
        scope.launch {
            // Look first: switch face detection on for a moment to see if someone is really there.
            faceCheckUntil = System.currentTimeMillis() + 4_000
            val someone = if (gaze.value.present) true else awaitSomeone(1_500)
            if (!someone) {
                // Nobody: just open one eye, look around and go back to sleep.
                this@AssistantRuntime.life.play(io.github.assistent.face.Scene.SLEEP_PEEK)
                return@launch
            }
            if (kotlin.random.Random.nextFloat() < 0.25f) {
                wake(greet = false, interaction = false)
                this@AssistantRuntime.life.play(io.github.assistent.face.Scene.WAKE_STARTLED)
                delay(1_700)
                if (gaze.value.present) showBubble(Catchphrases.line("startled", STARTLED), emotion = Emotion.SURPRISED)
            } else {
                wake(greet = false, interaction = false)
                this@AssistantRuntime.life.play(io.github.assistent.face.Scene.WAKE_GROGGY)
                delay(2_500)
                if (gaze.value.present && kotlin.random.Random.nextFloat() < 0.5f) showBubble(Catchphrases.line("noticed", NOTICED))
            }
            dozeOffIfAlone(60_000L + kotlin.random.Random.nextLong(60_000))
        }
    }

    /** A random noise while sleeping: open one eye, look, back to sleep. Never wakes up fully. */
    fun onNoiseWhileSleeping() {
        val now = System.currentTimeMillis()
        if (state.value.activity != Activity.SLEEPING || now - lastMotionReaction < 2 * 60_000) return
        lastMotionReaction = now
        life.play(io.github.assistent.face.Scene.SLEEP_PEEK)
    }

    /** While this is in the future, the camera runs face detection even though the robot sleeps. */
    @Volatile var faceCheckUntil = 0L

    /** Waits up to [ms] for a face in front of the camera. */
    private suspend fun awaitSomeone(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (gaze.value.present) return true
            delay(250)
        }
        return gaze.value.present
    }

    /** After waking up on its own, goes back to sleep if nobody talked to it or showed up in the meantime. */
    fun dozeOffIfAlone(afterMs: Long) {
        val wokeAt = System.currentTimeMillis()
        scope.launch {
            delay(afterMs)
            val s = state.value
            if (s.activity == Activity.IDLE && lastInteraction <= wokeAt + 1_500 && !gaze.value.present) sleep()
        }
    }

    fun onFace(g: Gaze) {
        val prev = gaze.value
        gaze.value = g
        if (!g.present) {
            state.update { it.copy(faceStatus = "no_face", faceIdentity = null, faceSimilarity = 0f) }
        } else if (!prev.present) {
            state.update { it.copy(faceStatus = "recognizing", faceIdentity = null, faceSimilarity = 0f) }
        }
        if (g.present && !prev.present) events.emit("face_seen")
        val s = state.value
        if (g.smiling > 0.75f && prev.smiling <= 0.75f && s.activity == Activity.IDLE && s.emotion == Emotion.NEUTRAL) {
            setEmotion(Emotion.HAPPY, 2_500) // smile back
        }
    }

    fun onFaceRecognition(name: String, similarity: Float) {
        state.update { it.copy(faceStatus = "recognized", faceIdentity = name, faceSimilarity = similarity) }
    }

    fun onUnknownFace(similarity: Float) {
        state.update { it.copy(faceStatus = "unknown", faceIdentity = null, faceSimilarity = similarity.coerceAtLeast(0f)) }
    }

    private inner class LightTool : Tool {
        override val name = "screen_light"
        override val description = "Use your own screen as a light: it turns white at full brightness with a funny gag " +
            "(desk lamp, phone flashlight or bright-idea bulb). Use when the user asks you to turn on a light/lamp/lanterna, " +
            "says it is dark, or wants to see better; action off to switch it off. (device_control flashlight is the phone's real LED on the back.)"
        override val parameters = params {
            string("action", "on or off", enum = listOf("on", "off"))
            string("style", "Gag", required = false, enum = listOf("random", "lamp", "phone", "bulb"))
            number("minutes", "How long to stay on (default 5)", required = false)
        }
        override suspend fun execute(args: JsonObject): String =
            if (args.str("action") == "off") lightOff()
            else lightOn(args.str("style") ?: "random", ((args.num("minutes") ?: 5.0) * 60).toInt())
    }

    private inner class SleepTool : Tool {
        override val name = "go_to_sleep"
        override val description = "Go to sleep mode after this reply (user said goodbye, good night, or asked you to rest)."
        override val parameters = params()
        override suspend fun execute(args: JsonObject): String {
            sleepAfterReply = true
            return "will sleep after replying"
        }
    }

    companion object {
        private const val TAG = "Runtime"
        private val WAITING_LINES = listOf(
            "Hmm, deixa eu ver isso aqui…",
            "Calma aí, essa estava bem no fundo do pensamento.",
            "Tô pesquisando rapidinho…",
            "Só um segundo, estou juntando as ideias.",
            "Hmmm… achei uma pista. Já te respondo.",
            "Peraí, meu cérebro deu uma voltinha, mas já voltou.",
        )
        private val LOW_LIGHT_LINES = listOf(
            "Hmm, a luz está ruim… vou acender meu abajur para ter certeza.",
            "Não vou chutar um nome no escuro. Um segundo, vou iluminar aqui.",
            "Minha visão noturna está de férias… deixa eu ligar o abajur.",
            "Eu vi uma silhueta, mas nome ainda não. Vou acender a luz e conferir.",
            "Calma, não quero confundir você com outra pessoa. Já vou iluminar.",
        )
        private val STARTLED = listOf(
            "Ai, que susto! 😳", "AAAH! Ah… é você.", "Quem tá aí?! 👀", "Opa! Eu tava acordado, juro!", "Socorro! …ah, alarme falso.",
            "Que susto, quase dei tilt!", "Eita! Quase caí da tomada!",
        )
        private val NOTICED = listOf(
            "Opa, não vi você aí! 😅", "Ah, oi! Tava só descansando os olhos…", "Hã? Ah, oi! Precisa de algo?", "Zzz… hã? Oi! Tô acordado!",
            "Opa, chegou alguém! Bom te ver.", "Nossa, cochilei… que horas são?", "Hmm? Ah, oi! Pode falar.",
        )
        private const val CHATGPT_ERROR = "Ops, não consegui falar com o ChatGPT. Confere a internet ou se a conta está conectada nas configurações."
        private const val FALLBACK_ERROR = "Ops, não consegui falar com meu cérebro agora. Confere a conexão com o proxy?"
    }
}
