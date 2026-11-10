package io.github.assistent.tools

import android.util.Log
import io.github.assistent.config.McpServerConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class McpTool(val name: String, val description: String, val schema: JSONObject)

/**
 * JSON-RPC 2.0 over Streamable HTTP (MCP spec).
 * No external SDK — just OkHttp + org.json.
 */
class McpClient(private val http: OkHttpClient, private val server: McpServerConfig) {

    private var initialized = false
    private val discovered = mutableListOf<McpTool>()
    private var lastError: String? = null

    val tools: List<McpTool> get() = discovered.toList()
    val error: String? get() = lastError

    fun connect(): Boolean {
        discovered.clear()
        lastError = null
        return try {
            rpc("initialize", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("clientInfo", JSONObject().apply { put("name", "assistent"); put("version", "1.0") })
                put("capabilities", JSONObject())
            }) ?: run { lastError = "initialize failed"; return false }
            fetchTools(null)
            initialized = true
            true
        } catch (e: Exception) {
            lastError = e.message
            Log.w("McpClient", "${server.name}: ${e.message}")
            false
        }
    }

    private fun fetchTools(cursor: String?) {
        val params = if (cursor != null) JSONObject().apply { put("cursor", cursor) } else JSONObject()
        val resp = rpc("tools/list", params) ?: return
        val list = resp.optJSONArray("tools") ?: return
        for (i in 0 until list.length()) {
            val t = list.getJSONObject(i)
            val rawName = t.optString("name")
            val safeName = "mcp_${server.name}_$rawName".replace(Regex("[^a-z0-9_]"), "_").take(60)
            discovered += McpTool(
                name = safeName,
                description = "via MCP server ${server.name}: ${t.optString("description")}",
                schema = t.optJSONObject("inputSchema") ?: JSONObject(),
            )
        }
        resp.optString("nextCursor").takeIf { it.isNotBlank() }?.let { fetchTools(it) }
    }

    suspend fun call(mcpToolName: String, arguments: JSONObject): String {
        // mcpToolName = raw MCP name (before prefix)
        return try {
            val params = JSONObject().apply {
                put("name", mcpToolName); put("arguments", arguments)
            }
            val resp = rpc("tools/call", params)
            if (resp == null) return "TOOL_ERROR: no response from ${server.name}"
            if (resp.optBoolean("isError", false)) return "TOOL_ERROR: ${extractText(resp)}"
            extractText(resp)
        } catch (e: Exception) {
            "TOOL_ERROR: ${e.message}"
        }
    }

    private fun extractText(resp: JSONObject): String {
        val content = resp.optJSONArray("content") ?: return resp.toString()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.getJSONObject(i)
            if (item.optString("type") == "text") sb.append(item.optString("text"))
        }
        return sb.toString().trim().ifBlank { resp.toString() }
    }

    private fun rpc(method: String, params: JSONObject): JSONObject? {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", System.currentTimeMillis())
            put("method", method); put("params", params)
        }.toString()
        val reqBuilder = Request.Builder()
            .url(server.url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
        if (server.token.isNotBlank()) reqBuilder.header("Authorization", "Bearer ${server.token}")
        return http.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                lastError = "HTTP ${resp.code}"
                return null
            }
            val text = resp.body?.string() ?: return null
            // Handle SSE: take the first data: line
            val jsonText = if (text.startsWith("data:")) {
                text.lines().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
            } else text
            JSONObject(jsonText ?: return null).optJSONObject("result")
        }
    }
}
