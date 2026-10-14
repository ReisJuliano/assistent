package io.github.assistent.brain

import io.github.assistent.config.LlmConfig
import io.github.assistent.core.AppJson
import io.github.assistent.core.EventDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class ToolCall(val id: String, val type: String = "function", val function: FunctionCall)

@Serializable
data class FunctionCall(val name: String, val arguments: String = "{}")

@Serializable
data class ToolSpec(val type: String = "function", val function: FunctionSpec)

@Serializable
data class FunctionSpec(val name: String, val description: String, val parameters: JsonObject)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    val stream: Boolean = false,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
private data class ChatResponse(val choices: List<Choice> = emptyList())

@Serializable
private data class Choice(val message: ChatMessage, @SerialName("finish_reason") val finishReason: String? = null)

class LlmException(message: String) : IOException(message)

/** The account's usage limit was reached; no calls until [untilMs]. */
class LlmLimitException(val untilMs: Long) : IOException("usage limit reached until $untilMs")

/** Shared "don't call until" gate, set when the provider answers 429 (e.g. ChatGPT plan usage limit). */
object LlmQuota {
    @Volatile var blockedUntil = 0L
    val blocked get() = System.currentTimeMillis() < blockedUntil

    fun check() { if (blocked) throw LlmLimitException(blockedUntil) }

    /** Reads `resets_in_seconds`/`retry-after` from a 429 and blocks until then (default 15 min). */
    fun onLimited(body: String, retryAfter: String?): LlmLimitException {
        val secs = Regex("\"resets_in_seconds\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: retryAfter?.toLongOrNull() ?: 900L
        blockedUntil = System.currentTimeMillis() + secs * 1000
        return LlmLimitException(blockedUntil)
    }
}

sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class Done(val message: ChatMessage) : StreamEvent
}

/**
 * Minimal OpenAI Chat Completions client. Works with CLIProxyAPI (ChatGPT/Codex OAuth, Gemini,
 * Claude…), OpenAI, OpenRouter, Ollama, LM Studio — anything speaking `/chat/completions`.
 */
class LlmClient(private val http: OkHttpClient) {
    /** Set by the app graph: the "Sign in with ChatGPT" account used when `llm.provider == "chatgpt"`. */
    var chatgpt: ChatGptAuth? = null
    private val responses by lazy { ResponsesClient(http) }

    private fun usesChatGpt(cfg: LlmConfig) = cfg.provider == "chatgpt"

    suspend fun complete(cfg: LlmConfig, messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage {
        if (usesChatGpt(cfg)) {
            var done: ChatMessage? = null
            stream(cfg, messages, tools).collect { if (it is StreamEvent.Done) done = it.message }
            return done ?: throw LlmException("LLM returned nothing")
        }
        return completeCompat(cfg, messages, tools)
    }

    private suspend fun completeCompat(cfg: LlmConfig, messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage =
        withContext(Dispatchers.IO) {
            LlmQuota.check()
            val body = AppJson.encodeToString(
                ChatRequest.serializer(),
                ChatRequest(cfg.model, messages, tools.ifEmpty { null }, reasoningEffort = cfg.reasoningEffort.ifBlank { null }),
            )
            val req = Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
                .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
                .post(body.toRequestBody(EventDispatcher.JSON))
                .build()
            client(cfg).newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code == 429) throw LlmQuota.onLimited(text, res.header("retry-after"))
                if (!res.isSuccessful) throw LlmException("LLM HTTP ${res.code}: ${text.take(300)}")
                val parsed = AppJson.decodeFromString(ChatResponse.serializer(), text)
                parsed.choices.firstOrNull()?.message ?: throw LlmException("LLM returned no choices")
            }
        }

    fun stream(cfg: LlmConfig, messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<StreamEvent> =
        if (usesChatGpt(cfg)) {
            val auth = chatgpt ?: throw LlmException("ChatGPT sign-in unavailable")
            responses.stream(cfg, messages, tools, auth)
        } else streamCompat(cfg, messages, tools)

    private fun streamCompat(cfg: LlmConfig, messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<StreamEvent> = flow {
        LlmQuota.check()
        val body = AppJson.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(cfg.model, messages, tools.ifEmpty { null }, stream = true, reasoningEffort = cfg.reasoningEffort.ifBlank { null }),
        )
        val req = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .post(body.toRequestBody(EventDispatcher.JSON))
            .build()
        val content = StringBuilder()
        val calls = linkedMapOf<Int, ToolCallBuilder>()
        client(cfg).newCall(req).execute().use { res ->
            if (res.code == 429) throw LlmQuota.onLimited(res.body?.string().orEmpty(), res.header("retry-after"))
            if (!res.isSuccessful) throw LlmException("LLM stream HTTP ${res.code}: ${res.body?.string().orEmpty().take(300)}")
            val source = res.body?.source() ?: throw LlmException("LLM stream returned no body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val choice = runCatching {
                    AppJson.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                }.getOrNull() ?: continue
                val delta = choice["delta"]?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                    content.append(it)
                    emit(StreamEvent.Content(it))
                }
                delta["tool_calls"]?.jsonArray?.forEach { mergeToolCall(it, calls) }
            }
        }
        emit(StreamEvent.Done(ChatMessage("assistant", content.toString().ifBlank { null }, toolCalls = calls.values.map { it.build() }.ifEmpty { null })))
    }.flowOn(Dispatchers.IO)

    /** `GET /models` — used by settings to list what the proxy exposes. */
    suspend fun listModels(cfg: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        if (usesChatGpt(cfg)) return@withContext responses.listModels(chatgpt ?: throw LlmException("ChatGPT sign-in unavailable"))
        val req = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/models")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .build()
        client(cfg).newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw LlmException("HTTP ${res.code}: ${text.take(200)}")
            AppJson.parseToJsonElement(text).jsonObject["data"]?.jsonArray
                ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                .orEmpty()
        }
    }

    private fun client(cfg: LlmConfig) =
        http.newBuilder().readTimeout(cfg.timeoutSeconds.toLong(), TimeUnit.SECONDS).build()

    private fun mergeToolCall(element: JsonElement, calls: MutableMap<Int, ToolCallBuilder>) {
        val obj = element.jsonObject
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: calls.size
        val builder = calls.getOrPut(index) { ToolCallBuilder() }
        obj["id"]?.jsonPrimitive?.contentOrNull?.let { builder.id.append(it) }
        obj["type"]?.jsonPrimitive?.contentOrNull?.let { builder.type = it }
        obj["function"]?.jsonObject?.let { fn ->
            fn["name"]?.jsonPrimitive?.contentOrNull?.let { builder.name.append(it) }
            fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { builder.arguments.append(it) }
        }
    }

    private class ToolCallBuilder {
        val id = StringBuilder()
        var type: String = "function"
        val name = StringBuilder()
        val arguments = StringBuilder()
        fun build() = ToolCall(
            id = id.toString().ifBlank { "call_${System.nanoTime()}" },
            type = type,
            function = FunctionCall(name.toString(), arguments.toString().ifBlank { "{}" }),
        )
    }
}
