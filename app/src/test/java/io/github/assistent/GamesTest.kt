package io.github.assistent

import io.github.assistent.games.GameMaster
import io.github.assistent.games.GameMaster.Companion.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamesTest {
    private val q = GameMaster.Q("Capital do Brasil?", listOf("Rio de Janeiro", "Brasília", "São Paulo", "Salvador"), 1, "")

    @Test fun spokenNumbers() {
        assertEquals(42, GameMaster.numberIn(normalize("quarenta e dois")))
        assertEquals(7, GameMaster.numberIn(normalize("é o 7")))
        assertEquals(100, GameMaster.numberIn(normalize("cem")))
        assertNull(GameMaster.numberIn(normalize("não sei")))
    }

    @Test fun quizAnswersByLetterOrByTheOptionItself() {
        assertEquals(1, GameMaster.pickOption(normalize("letra B"), q, false))
        assertEquals(2, GameMaster.pickOption(normalize("é a C"), q, false))
        assertEquals(1, GameMaster.pickOption(normalize("acho que é Brasília"), q, false))
        assertEquals(0, GameMaster.pickOption(normalize("a"), q, false))
        assertNull(GameMaster.pickOption(normalize("sei lá"), q, false))
        assertEquals(0, GameMaster.pickOption(normalize("verdade"), q, true))
        assertEquals(1, GameMaster.pickOption(normalize("é mentira"), q, true))
    }

    @Test fun parsesTheGeneratedQuestions() {
        val raw = """Aqui: [{"question":"2+2?","options":["3","4","5","6"],"answer":"B","explanation":"Simples."}]"""
        val qs = GameMaster.parseQuestions(raw, false)
        assertEquals(1, qs.size)
        assertEquals(1, qs[0].answer)
        val tf = GameMaster.parseQuestions("""[{"question":"O Sol é uma estrela.","answer":"verdade","explanation":""}]""", true)
        assertEquals(0, tf[0].answer)
    }
}
