package io.github.assistent.core

import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.data.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Proactively fetches BRL/USD rate and UV index on a schedule, saving results to memory so the
 * brain can answer "quanto tá o dólar?" or "qual o UV hoje?" instantly without a live search.
 *
 * Intervals shrink when the AmbientBuffer shows the topic is being discussed frequently.
 */
class ProactiveCache(
    private val memory: MemoryRepository,
    private val config: ConfigStore,
    private val http: OkHttpClient,
    private val ambient: AmbientBuffer,
    private val scope: CoroutineScope,
) {
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"))

    /** Supplies upcoming calendar events as a string; set by AssistentApp. */
    var calendarSource: (suspend () -> String?)? = null

    fun start() {
        scope.launch(Dispatchers.IO) { brlLoop() }
        scope.launch(Dispatchers.IO) { uvLoop() }
        scope.launch(Dispatchers.IO) { weatherSummaryLoop() }
        scope.launch(Dispatchers.IO) { calendarLoop() }
    }

    // ── BRL/USD ──────────────────────────────────────────────────────────────

    private suspend fun brlLoop() {
        while (true) {
            runCatching { fetchBrl() }.onFailure { Log.w(TAG, "BRL fetch: ${it.message}") }
            delay(intervalMs("dolar", 60 * 60_000L, 5 * 60_000L))
        }
    }

    private suspend fun fetchBrl() {
        val body = get("https://economia.awesomeapi.com.br/json/last/USD-BRL")
        val bid = JSONObject(body).getJSONObject("USDBRL").getString("bid").toDoubleOrNull() ?: return
        val text = "Cotação do dólar (USD/BRL): R$ %.2f (atualizado às %s)".format(bid, timeFmt.format(Date()))
        saveOrUpdate(text, "dolar câmbio moeda cotação")
    }

    // ── UV index ─────────────────────────────────────────────────────────────

    private suspend fun uvLoop() {
        // UV only makes sense while there is a configured location.
        while (true) {
            val place = config.current.weather.defaultLocation
            if (place.isNotBlank()) {
                runCatching { fetchUv(place) }.onFailure { Log.w(TAG, "UV fetch: ${it.message}") }
            }
            delay(intervalMs("uv sol", 2 * 60 * 60_000L, 30 * 60_000L))
        }
    }

    private suspend fun fetchUv(place: String) {
        // Geocode (open-meteo, free, same as LifeDirector)
        val geo = get(
            "https://geocoding-api.open-meteo.com/v1/search?name=${encode(place)}&count=1",
        )
        val r = JSONObject(geo).optJSONArray("results")?.optJSONObject(0) ?: return
        val lat = r.optDouble("latitude")
        val lon = r.optDouble("longitude")
        if (lat.isNaN() || lon.isNaN()) return

        val forecast = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=uv_index_max&timezone=auto&forecast_days=1",
        )
        val uv = JSONObject(forecast)
            .optJSONObject("daily")
            ?.optJSONArray("uv_index_max")
            ?.optDouble(0) ?: return

        val level = when {
            uv >= 11 -> "extremo"
            uv >= 8 -> "muito alto"
            uv >= 6 -> "alto"
            uv >= 3 -> "moderado"
            else -> "baixo"
        }
        val text = "Índice UV máximo em $place: %.0f ($level) — %s".format(uv, timeFmt.format(Date()))
        saveOrUpdate(text, "uv sol protetor solar radiação")
    }

    // ── Current weather summary ───────────────────────────────────────────────

    private suspend fun weatherSummaryLoop() {
        while (true) {
            val place = config.current.weather.defaultLocation
            if (place.isNotBlank()) {
                runCatching { fetchWeatherSummary(place) }.onFailure { Log.w(TAG, "weather summary: ${it.message}") }
            }
            delay(intervalMs("tempo clima chuva", 10 * 60_000L, 3 * 60_000L))
        }
    }

    private suspend fun fetchWeatherSummary(place: String) {
        val geo = get("https://geocoding-api.open-meteo.com/v1/search?name=${encode(place)}&count=1")
        val r = JSONObject(geo).optJSONArray("results")?.optJSONObject(0) ?: return
        val lat = r.optDouble("latitude"); val lon = r.optDouble("longitude")
        if (lat.isNaN() || lon.isNaN()) return
        val forecast = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto",
        )
        val cur = JSONObject(forecast).optJSONObject("current") ?: return
        val temp = cur.optDouble("temperature_2m")
        val humidity = cur.optDouble("relative_humidity_2m")
        val wind = cur.optDouble("wind_speed_10m")
        val code = cur.optInt("weather_code", -1)
        val desc = weatherCodeDescription(code)
        val text = "Clima atual em $place: ${"%.0f".format(temp)}°C, $desc, umidade ${"%.0f".format(humidity)}%, vento ${"%.0f".format(wind)} km/h (${timeFmt.format(Date())})"
        saveOrUpdate(text, "tempo clima temperatura chuva")
    }

    private fun weatherCodeDescription(code: Int) = when (code) {
        0 -> "céu limpo"; 1, 2, 3 -> "parcialmente nublado"
        45, 48 -> "neblina"; 51, 53, 55 -> "garoa"
        61, 63, 65 -> "chuva"; 71, 73, 75 -> "neve"
        80, 81, 82 -> "pancadas de chuva"; 95, 96, 99 -> "tempestade"
        else -> "condição desconhecida"
    }

    // ── Calendar ──────────────────────────────────────────────────────────────

    private suspend fun calendarLoop() {
        while (true) {
            val source = calendarSource
            if (source != null) {
                runCatching {
                    val text = source()
                    if (!text.isNullOrBlank()) saveOrUpdate(text, "agenda evento compromisso reunião")
                }.onFailure { Log.w(TAG, "calendar fetch: ${it.message}") }
            }
            delay(intervalMs("agenda evento reunião", 15 * 60_000L, 5 * 60_000L))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the short interval if any keyword from [topics] appeared ≥2 times in the ambient
     * buffer in the last hour, otherwise the normal interval.
     */
    private fun intervalMs(topics: String, normal: Long, hot: Long): Long {
        val ctx = ambient.contextSnippet() ?: return normal
        val keywords = topics.split(" ")
        val hits = keywords.count { ctx.contains(it, ignoreCase = true) }
        return if (hits >= 2) hot else normal
    }

    private suspend fun saveOrUpdate(text: String, tags: String) {
        // Replace the previous entry for this same kind (first keyword in tags).
        val key = tags.split(" ").first()
        val existing = memory.search(key, limit = 5).firstOrNull { it.tags.contains(key) && it.source == "proactive" }
        if (existing != null) memory.delete(existing.id)
        memory.add(text, tags = tags, source = "proactive", share = false)
        Log.d(TAG, "cached: $text")
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use {
            it.body?.string() ?: "{}"
        }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "ProactiveCache"
    }
}
