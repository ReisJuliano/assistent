package io.github.assistent

import io.github.assistent.brain.Brain
import io.github.assistent.core.ReminderPhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPhrasesTest {
    @Test fun fillsNameAndMessage() {
        assertEquals("Ana, hora do remédio: tomar dipirona.", ReminderPhrases.fill("{n}, hora do remédio: {msg}.", "Ana", "tomar dipirona."))
    }

    @Test fun dropsVocativeWithoutName() {
        assertEquals("Hora do remédio: tomar dipirona.", ReminderPhrases.fill("{n}, hora do remédio: {msg}.", "", "tomar dipirona"))
        assertEquals("Opa! Não esquece: beber água.", ReminderPhrases.fill("Opa, {n}! Não esquece: {msg}.", "", "beber água"))
        assertEquals("Psiu: ligar pra mãe.", ReminderPhrases.fill("Psiu, {n}: {msg}.", "", "ligar pra mãe"))
        assertEquals("Ei você me pediu pra lembrar: x.", ReminderPhrases.fill("Ei {n}, você me pediu pra lembrar: {msg}.", "", "x"))
    }

    @Test fun everyTemplateKeepsTheMessageAndNoPlaceholders() {
        for (type in ReminderPhrases.TEMPLATES.keys + "unknown") for (nag in 0..1) repeat(20) {
            val s = ReminderPhrases.template(type, "", "tomar vitamina D", nag)
            assertTrue(s, "tomar vitamina D" in s)
            assertFalse(s, "{" in s)
        }
    }

    @Test fun avoidsImmediateRepeats() {
        val recent = ArrayDeque<String>()
        val lines = List(3) { ReminderPhrases.template("generic", "Ana", "x", 0, recent) }
        assertEquals(3, lines.toSet().size)
    }

    @Test fun humourFollowsToneAndSetting() {
        assertNotNull(Brain.humorInstruction("descontraido", "auto"))
        assertNull(Brain.humorInstruction("formal", "auto"))
        assertNotNull(Brain.humorInstruction("formal", "always"))
        assertTrue(Brain.humorInstruction("descontraido", "never")!!.startsWith("Do not"))
        assertNotNull(Brain.humorInstruction(listOf("formal", "sarcastico"), "auto"))
    }

    @Test fun severalTonesAreBlended() {
        val p = io.github.assistent.config.PersonalityConfig(tones = listOf("descontraido", "dramatico"))
        val text = Brain.toneFor(p)
        assertTrue(text, text.startsWith("blend ALL"))
        assertTrue(text.contains(Brain.TONES.getValue("dramatico")))
        val single = io.github.assistent.config.PersonalityConfig(tone = "formal")
        assertEquals(Brain.TONES.getValue("formal"), Brain.toneFor(single))
    }
}
