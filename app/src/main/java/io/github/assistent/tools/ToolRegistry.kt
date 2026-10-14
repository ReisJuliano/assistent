package io.github.assistent.tools

import io.github.assistent.config.ConfigStore
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

/** Built-in tools + runtime-registered tools + one tool per configured webhook (live from config). */
class ToolRegistry(
    builtins: List<Tool>,
    private val config: ConfigStore,
    private val http: OkHttpClient,
) {
    private val tools = CopyOnWriteArrayList(builtins)

    fun register(tool: Tool) {
        tools.removeAll { it.name == tool.name }
        tools += tool
    }

    fun all(): List<Tool> = tools + config.current.webhooks.filter { it.enabled }.map { WebhookTool(http, it) }

    fun find(name: String): Tool? = all().firstOrNull { it.name == name }
}
