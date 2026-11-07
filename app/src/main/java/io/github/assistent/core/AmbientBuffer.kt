package io.github.assistent.core

/** Rolling 10-minute buffer of overheard speech not directed at the robot. Not persisted. */
class AmbientBuffer {
    private val windowMs = 10 * 60 * 1000L
    private val entries = ArrayDeque<Entry>()

    data class Entry(val text: String, val speaker: String?, val timestampMs: Long)

    /** Called with significant phrases (>50 chars) for permanent memory saving. */
    var onSignificant: ((String) -> Unit)? = null

    @Synchronized
    fun add(text: String, speaker: String? = null) {
        val now = System.currentTimeMillis()
        entries.addLast(Entry(text, speaker, now))
        while (entries.isNotEmpty() && now - entries.first().timestampMs > windowMs) entries.removeFirst()
        if (text.length > 50) onSignificant?.invoke(text)
    }

    @Synchronized
    fun contextSnippet(): String? {
        if (entries.isEmpty()) return null
        return entries.takeLast(8).joinToString("\n") { "[${it.speaker ?: "pessoa"}]: ${it.text}" }
    }

    @Synchronized
    fun clear() = entries.clear()
}
