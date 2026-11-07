package io.github.assistent.core

/** Rolling 10-minute buffer of overheard speech not directed at the robot. Not persisted. */
class AmbientBuffer {
    private val windowMs = 10 * 60 * 1000L
    private val entries = ArrayDeque<Entry>()

    data class Entry(val text: String, val speaker: String?, val timestampMs: Long)

    @Synchronized
    fun add(text: String, speaker: String? = null) {
        val now = System.currentTimeMillis()
        entries.addLast(Entry(text, speaker, now))
        while (entries.isNotEmpty() && now - entries.first().timestampMs > windowMs) entries.removeFirst()
    }

    @Synchronized
    fun contextSnippet(): String? {
        if (entries.isEmpty()) return null
        return entries.takeLast(8).joinToString("\n") { "[${it.speaker ?: "pessoa"}]: ${it.text}" }
    }

    @Synchronized
    fun clear() = entries.clear()
}
