package io.github.assistent

import io.github.assistent.voice.SpeechClip
import io.github.assistent.voice.SpeechPipeline
import io.github.assistent.voice.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SpeechAndWakeTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Synthesis takes 80 ms, playback 100 ms: with prefetch there must be (almost) no silence between clips. */
    @Test fun nextSentenceIsSynthesisedWhilePlaying() = runBlocking {
        val gaps = Collections.synchronizedList(mutableListOf<Long>())
        val played = Collections.synchronizedList(mutableListOf<String>())
        val p = SpeechPipeline(
            scope(),
            synthesize = { t -> delay(80); SpeechClip(t, ByteArray(2), 16_000) },
            play = { c -> delay(100); played += c.text },
            playedFraction = { 0f }, stopPlayback = {}, prefetch = 2,
            onGap = { gaps += it },
        )
        p.start()
        listOf("Um.", "Dois.", "Três.", "Quatro.").forEach(p::submit)
        p.finish()
        assertEquals(listOf("Um.", "Dois.", "Três.", "Quatro."), played)
        assertTrue("gaps $gaps", gaps.all { it < 40 })
    }

    @Test fun cancelReturnsWhatWasHeardIncludingPartOfTheCurrentSentence() = runBlocking {
        var fraction = 0f
        var stopped = false
        val p = SpeechPipeline(
            scope(),
            synthesize = { t -> SpeechClip(t, ByteArray(2), 16_000) },
            play = { delay(10_000) },
            playedFraction = { fraction }, stopPlayback = { stopped = true },
        )
        p.start()
        p.submit("um dois três quatro")
        delay(200)
        fraction = 0.5f
        assertEquals("um dois", p.cancel())
        assertTrue(stopped)
        assertEquals("um dois", p.cancel()) // same answer after the sound is gone
    }

    @Test fun aNameInTheMiddleOfASentenceIsNotACall() {
        val mid = """{"result":[{"conf":1.0,"word":"[unk]","start":0.1,"end":0.4},{"conf":0.9,"word":"parafuso","start":0.5,"end":1.0}],"text":"[unk] parafuso"}"""
        val call = """{"result":[{"conf":1.0,"word":"ei","start":0.1,"end":0.3},{"conf":0.9,"word":"parafuso","start":0.3,"end":0.9},{"conf":1.0,"word":"[unk]","start":1.0,"end":1.5}],"text":"ei parafuso [unk]"}"""
        val phrases = listOf(listOf("parafuso"))
        assertFalse(WakeWordEngine.grammarHit(mid, phrases)!!.atStart)
        assertFalse(WakeWordEngine.isCall(WakeWordEngine.grammarHit(mid, phrases)!!, emptyList()))
        val midFree = listOf(io.github.assistent.voice.TimedWord("comprei", 0.1f, 0.4f), io.github.assistent.voice.TimedWord("parafuso", 0.5f, 1.0f))
        assertFalse(WakeWordEngine.isCall(WakeWordEngine.grammarHit(mid, phrases)!!, midFree))
        val hit = WakeWordEngine.grammarHit(call, phrases)!!
        assertTrue(hit.atStart)
        assertEquals(0.9f, hit.endSec, 1e-4f)
        val free = """{"result":[{"word":"ei","start":0.1},{"word":"parafuso","start":0.3},{"word":"que","start":1.0},{"word":"horas","start":1.2},{"word":"são","start":1.5}],"text":"ei parafuso que horas são"}"""
        assertEquals("que horas são", WakeWordEngine.wordsAfter(free, hit.endSec))
    }

    @Test fun aLongerWordContainingTheNameIsNotACall() {
        val hit = io.github.assistent.voice.GrammarHit("nexo", 1f, 0.3f, 0.8f, atStart = true)
        val anexo = listOf(io.github.assistent.voice.TimedWord("anexo", 0.2f, 0.8f), io.github.assistent.voice.TimedWord("chegou", 0.9f, 1.3f))
        val nexo = listOf(io.github.assistent.voice.TimedWord("nexo", 0.3f, 0.8f), io.github.assistent.voice.TimedWord("liga", 0.9f, 1.2f))
        val nothing = listOf(io.github.assistent.voice.TimedWord("que", 1.0f, 1.2f))
        val after = listOf(io.github.assistent.voice.TimedWord("comprei", -1.0f, 0.2f))
        assertFalse(WakeWordEngine.isCall(hit, anexo))
        assertTrue(WakeWordEngine.isCall(hit, nexo))
        assertTrue(WakeWordEngine.isCall(hit, nothing))
        assertFalse(WakeWordEngine.isCall(hit, after))
    }

    @Test fun grammarHitUsesTheWeakestWordConfidence() {
        val json = """{"result":[{"conf":0.98,"word":"ei"},{"conf":0.71,"word":"parafuso"},{"conf":1.0,"word":"[unk]"}],"text":"ei parafuso [unk]"}"""
        val hit = WakeWordEngine.grammarHit(json, listOf(listOf("ei", "parafuso"), listOf("parafuso")))
        assertNotNull(hit)
        assertEquals("ei parafuso", hit!!.first)
        assertEquals(0.71f, hit.second, 1e-4f)
        assertNull(WakeWordEngine.grammarHit("""{"result":[{"conf":1.0,"word":"[unk]"}],"text":"[unk]"}""", listOf(listOf("parafuso"))))
    }

    @Test fun accentsAreKeptForTheModelButIgnoredForMatching() {
        assertEquals(listOf("ei", "robô"), WakeWordEngine.spokenWords("Ei, Robô!"))
        assertTrue(WakeWordEngine.containsPhrase(listOf("oi", "ei", "parafuso", "tudo"), listOf("ei", "parafuso")))
        assertFalse(WakeWordEngine.containsPhrase(listOf("parafuso"), listOf("ei", "parafuso")))
    }

    @Test fun fuzzyFallbackNoLongerConfusesWordsThatOnlyShareConsonants() {
        val phrases = listOf(listOf("nexo"))
        assertNull(WakeWordEngine.match("um nicho", phrases, 0.72f))
        assertNull(WakeWordEngine.match("nada", phrases, 0.72f))
        assertNotNull(WakeWordEngine.match("nexo que horas são", phrases, 0.72f))
    }
}
