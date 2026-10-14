package io.github.assistent.tools

import io.github.assistent.config.WebhookDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Wraps a user-defined [WebhookDef] as an LLM tool. This is the generic bridge to
 * Home Assistant, Node-RED, n8n, IFTTT, Tasmota, Shelly, your own server…
 */
class WebhookTool(private val http: OkHttpClient, val def: WebhookDef) : Tool {
    override val name = "hook_" + def.id.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    override val description = def.description
    override val parameters = params { def.params.forEach { string(it.name, it.description, it.required) } }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        fun fill(s: String, encode: Boolean = false) = Regex("\\{\\{\\s*(\\w+)\\s*\\}\\}").replace(s) { m ->
            val v = args.str(m.groupValues[1]).orEmpty()
            if (encode) java.net.URLEncoder.encode(v, "UTF-8") else v.replace("\\", "\\\\").replace("\"", "\\\"")
        }
        val method = def.method.uppercase()
        val contentType = def.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "application/json"
        val body = if (method in setOf("GET", "HEAD", "DELETE") && def.body.isBlank()) null
        else fill(def.body).toRequestBody(contentType.toMediaTypeOrNull())
        val req = Request.Builder().url(fill(def.url, encode = true)).method(method, body)
            .apply { def.headers.forEach { (k, v) -> header(k, fill(v)) } }
            .build()
        http.newCall(req).execute().use { res ->
            "HTTP ${res.code}: ${res.body?.string().orEmpty().take(1500)}"
        }
    }
}
