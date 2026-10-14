package io.github.assistent.tools

import io.github.assistent.config.ConfigStore
import io.github.assistent.config.SearchConfig
import io.github.assistent.core.Activity
import io.github.assistent.core.AppJson
import io.github.assistent.core.EventDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

@Serializable
data class SearchResult(val title: String, val url: String, val snippet: String)

internal fun OkHttpClient.getText(url: String, headers: Map<String, String> = emptyMap()): String {
    val req = Request.Builder().url(url).header("User-Agent", UA).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    newCall(req).execute().use { res ->
        val body = res.body?.string().orEmpty()
        if (!res.isSuccessful) error("HTTP ${res.code} from ${req.url.host}")
        return body
    }
}

internal fun htmlToText(html: String): String = html
    .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h\\d>"), "\n")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
    .replace(Regex("\\n\\s*\\n+"), "\n")
    .trim()

class WebSearchTool(private val http: OkHttpClient, private val config: ConfigStore) : Tool {
    override val name = "web_search"
    override val description = "Search the web for current information, news, facts, prices, sports results, etc."
    override val activity = Activity.SEARCHING
    override val parameters = params { string("query", "Search query, in the most effective language") }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val q = args.str("query") ?: return@withContext "missing query"
        val cfg = config.current.search
        val results = when (cfg.provider) {
            "searxng" -> searxng(q, cfg)
            "brave" -> brave(q, cfg)
            "tavily" -> tavily(q, cfg)
            else -> duckduckgo(q)
        }.take(cfg.maxResults)
        if (results.isEmpty()) "no results" else AppJson.encodeToString(ListSerializer(SearchResult.serializer()), results)
    }

    private fun duckduckgo(q: String): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder().addQueryParameter("q", q).build().toString()
        val html = http.getText(url)
        val links = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        return links.mapIndexed { i, m ->
            var href = m.groupValues[1]
            Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            SearchResult(htmlToText(m.groupValues[2]), href, snippets.getOrNull(i)?.let { htmlToText(it.groupValues[1]) }.orEmpty())
        }.filterNot { it.url.contains("duckduckgo.com/y.js") }
    }

    private fun searxng(q: String, cfg: SearchConfig): List<SearchResult> {
        require(cfg.searxngUrl.isNotBlank()) { "searxngUrl not configured" }
        val url = (cfg.searxngUrl.trimEnd('/') + "/search").toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("format", "json").build().toString()
        val json = AppJson.parseToJsonElement(http.getText(url)).jsonObject
        return json["results"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            SearchResult(o.str("title").orEmpty(), o.str("url").orEmpty(), o.str("content").orEmpty())
        }
    }

    private fun brave(q: String, cfg: SearchConfig): List<SearchResult> {
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder().addQueryParameter("q", q).build().toString()
        val json = AppJson.parseToJsonElement(http.getText(url, mapOf("X-Subscription-Token" to cfg.braveApiKey, "Accept" to "application/json"))).jsonObject
        return json["web"]?.jsonObject?.get("results")?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            SearchResult(o.str("title").orEmpty(), o.str("url").orEmpty(), htmlToText(o.str("description").orEmpty()))
        }
    }

    private fun tavily(q: String, cfg: SearchConfig): List<SearchResult> {
        val body = buildJsonObject { put("api_key", cfg.tavilyApiKey); put("query", q); put("max_results", cfg.maxResults) }
        val req = Request.Builder().url("https://api.tavily.com/search")
            .post(body.toString().toRequestBody(EventDispatcher.JSON)).build()
        val text = http.newCall(req).execute().use { it.body?.string().orEmpty() }
        return AppJson.parseToJsonElement(text).jsonObject["results"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            SearchResult(o.str("title").orEmpty(), o.str("url").orEmpty(), o.str("content").orEmpty())
        }
    }
}

class FetchUrlTool(private val http: OkHttpClient) : Tool {
    override val name = "read_webpage"
    override val description = "Download a web page and return its readable text. Use after web_search when snippets are not enough."
    override val activity = Activity.SEARCHING
    override val parameters = params { string("url", "Full http(s) URL") }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args.str("url") ?: return@withContext "missing url"
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext "only http(s) URLs"
        htmlToText(http.getText(url)).take(6000)
    }
}

class WeatherTool(private val http: OkHttpClient, private val config: ConfigStore) : Tool {
    override val name = "get_weather"
    override val description = "Current weather and 3-day forecast (temperature °C, rain chance, wind) for a city. Use city, state/province and country when ambiguous. Omit location for the user's configured location."
    override val activity = Activity.SEARCHING
    override val parameters = params { string("location", "City name, e.g. 'Curitiba'", required = false) }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val place = args.str("location")?.takeIf { it.isNotBlank() } ?: config.current.weather.defaultLocation
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", place).addQueryParameter("count", "1").addQueryParameter("language", "pt").build().toString()
        val geo = AppJson.parseToJsonElement(http.getText(geoUrl)).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return@withContext "location '$place' not found"
        val lat = geo["latitude"]!!.jsonPrimitive.content
        val lon = geo["longitude"]!!.jsonPrimitive.content
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat).addQueryParameter("longitude", lon)
            .addQueryParameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "3").build().toString()
        val w = AppJson.parseToJsonElement(http.getText(url)).jsonObject
        val cur = w["current"]!!.jsonObject
        val daily = w["daily"]!!.jsonObject
        buildString {
            append("${geo.str("name")}, ${geo.str("admin1").orEmpty()} ${geo.str("country").orEmpty()}\n")
            append("now: ${cur.str("temperature_2m")}°C (feels ${cur.str("apparent_temperature")}°C), ")
            append("${wmo(cur.str("weather_code"))}, humidity ${cur.str("relative_humidity_2m")}%, wind ${cur.str("wind_speed_10m")} km/h\n")
            val days = daily["time"]!!.jsonArray
            for (i in days.indices) {
                append("${days[i].jsonPrimitive.content}: ${wmo(daily["weather_code"]!!.jsonArray[i].jsonPrimitive.content)}, ")
                append("min ${daily["temperature_2m_min"]!!.jsonArray[i].jsonPrimitive.content}°C ")
                append("max ${daily["temperature_2m_max"]!!.jsonArray[i].jsonPrimitive.content}°C, ")
                append("rain ${daily["precipitation_probability_max"]!!.jsonArray[i].jsonPrimitive.content}%\n")
            }
        }
    }

    private fun wmo(code: String?): String = when (code?.toIntOrNull()) {
        0 -> "clear sky"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorm"
        else -> "unknown"
    }
}
