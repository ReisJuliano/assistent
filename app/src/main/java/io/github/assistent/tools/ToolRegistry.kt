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

    /** Set by the app graph: every run becomes an observable operation (HUD + events + cancellable). */
    var operations: io.github.assistent.core.Operations? = null

    /** Runs a tool the same way for the brain, the API and the mesh. */
    suspend fun run(tool: Tool, args: kotlinx.serialization.json.JsonObject): String =
        operations?.track(tool.name, args) { tool.execute(args) } ?: tool.execute(args)
}
