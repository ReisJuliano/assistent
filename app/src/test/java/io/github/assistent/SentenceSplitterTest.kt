package io.github.assistent

import io.github.assistent.core.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {
    @Test
    fun releasesTheFirstLongClauseBeforeTheFinalPeriod() {
        val splitter = SentenceSplitter()
        val result = splitter.push("Esta é uma resposta longa o bastante para preparar a fala, enquanto eu continuo pensando")

        assertEquals(1, result.size)
        assertTrue(result.single().endsWith(","))
    }

    @Test
    fun doesNotSplitInsideTimeOrDecimal() {
        val splitter = SentenceSplitter()
        assertTrue(splitter.push("A reunião é às 7:30 e custa 3.50 reais hoje").isEmpty())
        assertEquals("A reunião é às 7:30 e custa 3.50 reais hoje.", splitter.flush() + ".")
    }
}
