package io.github.assistent.tools

import android.util.Log
import io.github.assistent.config.ConfigStore
import io.github.assistent.config.McpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Observes config, connects enabled MCP servers, registers/unregisters their tools. */
class McpManager(
    private val config: ConfigStore,
    private val registry: ToolRegistry,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val clients = mutableMapOf<String, McpClient>()

    fun start() {
        scope.launch(Dispatchers.IO) {
            config.config.collect { cfg ->
                sync(cfg.mcp.servers)
            }
        }
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            sync(config.current.mcp.servers)
        }
    }

    private fun sync(servers: List<McpServerConfig>) {
        val enabled = servers.filter { it.enabled }
        // Disconnect removed/disabled servers
        val remove = clients.keys - enabled.map { it.name }.toSet()
        for (name in remove) {
            registry.unregister("mcp_${name}_")
            clients.remove(name)
            Log.i("McpManager", "disconnected: $name")
        }
        // Connect new/changed servers
        for (server in enabled) {
            val existing = clients[server.name]
            if (existing != null) continue
            val client = McpClient(http, server)
            if (client.connect()) {
                clients[server.name] = client
                for (tool in client.tools) {
                    registry.register(McpDynamicTool(tool, client))
                }
                Log.i("McpManager", "connected ${server.name}: ${client.tools.size} tools")
            } else {
                Log.w("McpManager", "failed ${server.name}: ${client.error}")
            }
        }
    }

    fun status(): List<Map<String, Any>> = config.current.mcp.servers.map { server ->
        val client = clients[server.name]
        mapOf(
            "name" to server.name,
            "url" to server.url,
            "enabled" to server.enabled,
            "connected" to (client != null),
            "tools" to (client?.tools?.size ?: 0),
            "error" to (if (client != null) "" else "not connected"),
        )
    }
}

/** Tool proxy: bridges ToolRegistry (kotlinx JsonObject) to McpClient (org.json JSONObject). */
private class McpDynamicTool(
    private val mcpTool: McpTool,
    private val client: McpClient,
) : Tool {
    override val name = mcpTool.name
    override val description = mcpTool.description
    override val parameters: JsonObject = run {
        val schema = mcpTool.schema
        // Build a minimal JsonObject from the JSONObject schema
        val props = schema.optJSONObject("properties")
        val required = schema.optJSONArray("required")
        params {
            if (props != null) {
                for (key in props.keys()) {
                    val prop = props.getJSONObject(key)
                    val type = prop.optString("type", "string")
                    val desc = prop.optString("description", key)
                    val isRequired = required != null && (0 until required.length()).any { required.getString(it) == key }
                    when (type) {
                        "number", "integer" -> number(key, desc, required = isRequired)
                        "boolean" -> boolean(key, desc, required = isRequired)
                        else -> string(key, desc, required = isRequired)
                    }
                }
            }
        }
    }

    override suspend fun execute(args: JsonObject): String {
        // Convert kotlinx JsonObject to org.json JSONObject
        val jsonArgs = JSONObject()
        for ((k, v) in args) {
            val primitive = v as? JsonPrimitive ?: continue
            when {
                primitive.isString -> jsonArgs.put(k, primitive.content)
                primitive.content == "true" || primitive.content == "false" -> jsonArgs.put(k, primitive.content.toBoolean())
                else -> primitive.content.toLongOrNull()?.let { jsonArgs.put(k, it) }
                    ?: primitive.content.toDoubleOrNull()?.let { jsonArgs.put(k, it) }
                    ?: jsonArgs.put(k, primitive.content)
            }
        }
        // Raw MCP tool name = strip "mcp_{serverName}_" prefix
        val prefix = name.substringBefore("_", "") + "_" + name.substringAfter("_").substringBefore("_") + "_"
        val rawName = name.removePrefix(prefix)
        return client.call(rawName, jsonArgs)
    }
}
