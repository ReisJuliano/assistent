package io.github.assistent.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Keeps streamed generation independent from playback. The producer can stay ahead by a few chunks and
 * cancellation closes both the pending text and the current Speaker operation.
 */
class SpeechPipeline(
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    capacity: Int = 2,
    private val onFirstChunk: () -> Unit,
    private val onHeard: (String) -> Unit,
) {
    private val queue = Channel<String>(capacity.coerceIn(2, 8))
    private var worker: Job? = null
    private var started = false

    fun start() {
        if (worker != null) return
        worker = scope.launch {
            try {
                for (chunk in queue) {
                    if (!started) {
                        started = true
                        onFirstChunk()
                    }
                    speaker.speak(chunk)
                    onHeard(chunk)
                }
            } catch (_: CancellationException) {
                speaker.stop()
            }
        }
    }

    suspend fun submit(chunk: String) {
        if (chunk.isNotBlank()) queue.send(chunk)
    }

    suspend fun finish() {
        queue.close()
        worker?.join()
    }

    fun cancel() {
        queue.close()
        worker?.cancel()
        speaker.stop()
    }
}
