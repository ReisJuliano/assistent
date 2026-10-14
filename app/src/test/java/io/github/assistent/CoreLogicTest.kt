package io.github.assistent

import io.github.assistent.brain.Brain
import io.github.assistent.config.ConfigStore
import io.github.assistent.core.Emotion
import io.github.assistent.voice.WakeWordEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreLogicTest {
    private val phrases = listOf("ei nexo", "oi nexo", "olá nexo", "nexo", "anexo")
        .map(WakeWordEngine::words).sortedByDescending { it.size }

    @Test fun wakePhraseReturnsCommandAfterIt() {
        assertEquals("que horas são", WakeWordEngine.match("ei nexo que horas são", phrases, 0.75f))
    }

    @Test fun wakeIgnoresAccentsAndSmallMisrecognitions() {
        assertEquals("", WakeWordEngine.match("ola nexo", phrases, 0.75f))
        assertEquals("liga a luz", WakeWordEngine.match("ei nexu liga a luz", phrases, 0.75f))
    }

    @Test fun wakeDoesNotFireOnUnrelatedSpeech() {
        assertNull(WakeWordEngine.match("vamos almoçar mais tarde", phrases, 0.75f))
    }

    @Test fun emotionTagIsParsedAndStripped() {
        val (e, text) = Brain.parseEmotion("[joy] **Oba!** Vai fazer sol amanhã.")
        assertEquals(Emotion.JOY, e)
        assertEquals("Oba! Vai fazer sol amanhã.", text)
    }

    @Test fun missingTagFallsBackToNeutral() {
        assertEquals(Emotion.NEUTRAL, Brain.parseEmotion("Sem tag").first)
    }

    @Test fun configPatchMergesDeeply() {
        val base = Json.parseToJsonElement("""{"face":{"color":"#3DE8FF","glow":0.6},"llm":{"model":"a"}}""")
        val patch = Json.parseToJsonElement("""{"face":{"color":"#FF0000"}}""")
        val merged = ConfigStore.merge(base, patch).jsonObject
        assertEquals("#FF0000", merged["face"]!!.jsonObject["color"]!!.jsonPrimitive.content)
        assertEquals("0.6", merged["face"]!!.jsonObject["glow"]!!.jsonPrimitive.content)
        assertEquals("a", merged["llm"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }
}
