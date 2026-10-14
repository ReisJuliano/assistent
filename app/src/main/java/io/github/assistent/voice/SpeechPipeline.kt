package io.github.assistent.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Synthesised speech for one chunk of text, ready to play. */
class SpeechClip(val text: String, val pcm: ByteArray, val sampleRate: Int)

/**
 * Speaks streamed text without gaps and without slowing the LLM down:
 *
 * ```
 * submit(text) ─► texts ─► synthesizer (runs up to [prefetch] clips ahead) ─► clips ─► player
 * ```
 *
 * Synthesis of the next sentence runs while the current one plays. [heardText] is what was actually played
 * (whole clips plus the played fraction of the current one), which is what an interruption should remember.
 */
class SpeechPipeline(
    private val scope: CoroutineScope,
    private val synthesize: suspend (String) -> SpeechClip?,
    private val play: suspend (SpeechClip) -> Unit,
    /** 0..1 of the clip currently playing (from the player's head position). */
    private val playedFraction: () -> Float,
    private val stopPlayback: () -> Unit,
    prefetch: Int = 2,
    private val onFirstChunk: () -> Unit = {},
    private val onHeard: (String) -> Unit = {},
    /** Silence between the end of one clip and the start of the next, in ms (for latency logs/tests). */
    private val onGap: (Long) -> Unit = {},
) {
    private val texts = Channel<String>(Channel.UNLIMITED)
    private val clips = Channel<SpeechClip>(prefetch.coerceIn(1, 4))
    private var synthJob: Job? = null
    private var playJob: Job? = null
    private val heard = StringBuilder()
    @Volatile private var current: SpeechClip? = null
    @Volatile private var started = false

    fun start() {
        if (playJob != null) return
        synthJob = scope.launch {
            try {
                for (t in texts) synthesize(t)?.let { clips.send(it) }
            } finally {
                clips.close()
            }
        }
        playJob = scope.launch {
            var lastEnd = 0L
            for (c in clips) {
                if (!started) { started = true; onFirstChunk() } else onGap(System.currentTimeMillis() - lastEnd)
                current = c
                play(c)
                current = null
                lastEnd = System.currentTimeMillis()
                synchronized(heard) { heard.append(c.text).append(' ') }
                onHeard(c.text)
            }
        }
    }

    /** Never suspends: the LLM stream keeps flowing while speech catches up. */
    fun submit(text: String) {
        if (text.isNotBlank()) texts.trySend(text.trim())
    }

    /** No more text: wait until everything submitted has been spoken. */
    suspend fun finish() {
        texts.close()
        playJob?.join()
    }

    @Volatile private var cancelledHeard: String? = null

    /** Stops now (sound included) and returns what the listener actually heard (same answer if called again). */
    fun cancel(): String {
        cancelledHeard?.let { return it }
        val said = heardText()
        cancelledHeard = said
        texts.close()
        synthJob?.cancel()
        playJob?.cancel()
        stopPlayback()
        return said
    }

    /** Text the listener has heard so far: finished clips plus the played part of the current one. */
    fun heardText(): String {
        val done = synchronized(heard) { heard.toString() }
        val c = current ?: return done.trim()
        val words = c.text.split(' ')
        val k = (words.size * playedFraction().coerceIn(0f, 1f)).toInt()
        return (done + words.take(k).joinToString(" ")).trim()
    }

    /** The sentence playing right now (the robot's own words must never count as an interruption). */
    fun speakingNow(): String = current?.text.orEmpty()
}
