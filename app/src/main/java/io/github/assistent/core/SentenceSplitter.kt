package io.github.assistent.core

/**
 * Accumulates streamed text and releases speech-sized sentences.
 *
 * It waits for real sentence endings and avoids common false stops such as "Sr." and decimal numbers.
 */
class SentenceSplitter(private val minChars: Int = 25) {
    private val buffer = StringBuilder()
    private val abbreviations = setOf("sr", "sra", "dr", "dra", "prof", "profa", "etc", "ex")
    private var releasedFirstChunk = false

    fun push(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        buffer.append(text)
        val out = mutableListOf<String>()
        while (true) {
            val idx = findBoundary() ?: break
            val sentence = buffer.substring(0, idx + 1).replace(Regex("\\s+"), " ").trim()
            buffer.delete(0, idx + 1)
            if (sentence.isNotBlank()) out += sentence
        }
        return out
    }

    fun flush(): String = buffer.toString().replace(Regex("\\s+"), " ").trim().also { buffer.clear() }

    private fun findBoundary(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            val firstChunkBoundary = c == ',' || c == ';' || c == ':'
            if (c != '.' && c != '!' && c != '?' && c != '\n' && c != '…' && !firstChunkBoundary) continue
            if (c == '.' && isDecimalPoint(i)) continue
            if (c == '.' && isAbbreviation(i)) continue
            if (c == ':' && isTimeColon(i)) continue
            val required = if (!releasedFirstChunk && firstChunkBoundary) 40 else minChars
            if (i + 1 < required && c != '\n') continue
            val next = buffer.getOrNull(i + 1)
            if ((next == null || next.isWhitespace()) && (!releasedFirstChunk || !firstChunkBoundary || i >= 40)) {
                if (firstChunkBoundary) releasedFirstChunk = true
                return i
            }
        }
        if (!releasedFirstChunk && buffer.length > 220) {
            val cut = buffer.substring(0, 220).lastIndexOf(' ')
            if (cut >= 40) {
                releasedFirstChunk = true
                return cut - 1
            }
        }
        return null
    }

    private fun isDecimalPoint(i: Int): Boolean =
        buffer.getOrNull(i - 1)?.isDigit() == true && buffer.getOrNull(i + 1)?.isDigit() == true

    private fun isAbbreviation(i: Int): Boolean {
        var start = i - 1
        while (start >= 0 && buffer[start].isLetter()) start--
        val word = buffer.substring(start + 1, i).lowercase()
        return word in abbreviations
    }

    private fun isTimeColon(i: Int): Boolean =
        buffer.getOrNull(i - 1)?.isDigit() == true && buffer.getOrNull(i + 1)?.isDigit() == true
}
