package io.github.assistent.brain

import io.github.assistent.config.LlmConfig
import io.github.assistent.core.AppJson
import io.github.assistent.core.EventDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The public Responses API (`api.openai.com/v1/responses`) driven with a "Sign in with ChatGPT" plan token,
 * translated to/from the Chat Completions shapes the rest of the app speaks ([ChatMessage], [ToolSpec]).
 * Plan-token rules: `store=false`, `stream=true`, no system items (→ `instructions` / developer messages),
 * no sampling parameters.
 */
class ResponsesClient(private val http: OkHttpClient) {

    fun stream(cfg: LlmConfig, messages: List<ChatMessage>, tools: List<ToolSpec>, auth: ChatGptAuth): Flow<StreamEvent> = flow {
        LlmQuota.check()
        val body = AppJson.encodeToString(JsonObject.serializer(), buildBody(cfg, messages, tools))
        val client = http.newBuilder().readTimeout(cfg.timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        var token = auth.accessToken()
        var attempt = 0
        while (true) {
            val req = Request.Builder().url("$BASE/responses")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(EventDispatcher.JSON))
                .build()
            val res = client.newCall(req).execute()
            if (res.code == 401 && attempt++ == 0) {
                // Expired/revoked access token: one forced refresh, then give up (refresh errors are terminal there).
                res.close()
                token = auth.accessToken(forceRefresh = true)
                continue
            }
            res.use {
                if (!res.isSuccessful) throw failure(res.code, res.body?.string().orEmpty())
                val source = res.body?.source() ?: throw LlmException("Responses: empty body")
                val text = StringBuilder()
                val calls = mutableListOf<ToolCall>()
                var completed = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val ev = runCatching { AppJson.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject }.getOrNull() ?: continue
                    when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                        "response.output_text.delta" -> ev["delta"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                            text.append(it); emit(StreamEvent.Content(it))
                        }
                        "response.output_item.done" -> functionCall(ev["item"]?.jsonObject)?.let(calls::add)
                        "response.completed" -> { completed = true; break }
                        "response.failed", "error" -> throw failure(200, ev.toString())
                    }
                }
                if (!completed) throw LlmException("Responses: stream ended before response.completed")
                emit(StreamEvent.Done(ChatMessage("assistant", text.toString().ifBlank { null }, toolCalls = calls.ifEmpty { null })))
            }
            break
        }
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(auth: ChatGptAuth): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/models").header("Authorization", "Bearer ${auth.accessToken()}").build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw failure(r.code, text)
            AppJson.parseToJsonElement(text).jsonObject["data"]?.jsonArray
                ?.map { it.jsonObject }
                ?.filter { (it["visibility"]?.jsonPrimitive?.contentOrNull ?: "list") == "list" }
                ?.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
        }
    }

    private fun failure(http: Int, body: String): Exception {
        val code = Regex("\"code\"\\s*:\\s*\"([a-z0-9_]+)\"").find(body)?.groupValues?.get(1).orEmpty()
        return when {
            code == "subscription_sharing_usage_limit_exceeded" || http == 429 -> {
                // No reset time is given (an app-specific weekly cap may apply): pause for a while.
                LlmQuota.blockedUntil = System.currentTimeMillis() + 15 * 60_000
                LlmLimitException(LlmQuota.blockedUntil)
            }
            code == "subscription_sharing_user_not_eligible" -> LlmException("Sua conta ChatGPT não pode usar o plano em apps (precisa de Plus/Pro)")
            else -> LlmException("Responses HTTP $http ${code.ifBlank { body.take(300) }}")
        }
    }

    companion object {
        const val BASE = "https://api.openai.com/v1"

        /** Chat Completions history + tools → Responses request body. */
        fun buildBody(cfg: LlmConfig, messages: List<ChatMessage>, tools: List<ToolSpec>): JsonObject = buildJsonObject {
            put("model", cfg.model)
            put("store", false)
            put("stream", true)
            val system = messages.filter { it.role == "system" }
            system.firstOrNull()?.content?.let { put("instructions", it) }
            put("input", buildJsonArray {
                for (m in messages) {
                    when (m.role) {
                        "system" -> if (m !== system.first()) add(buildJsonObject { put("role", "developer"); put("content", m.content.orEmpty()) })
                        "user" -> add(buildJsonObject { put("role", "user"); put("content", m.content.orEmpty()) })
                        "assistant" -> {
                            m.content?.takeIf { it.isNotBlank() }?.let { c -> add(buildJsonObject { put("role", "assistant"); put("content", c) }) }
                            m.toolCalls?.forEach { tc ->
                                add(buildJsonObject {
                                    put("type", "function_call"); put("call_id", tc.id); put("name", tc.function.name); put("arguments", tc.function.arguments)
                                })
                            }
                        }
                        "tool" -> add(buildJsonObject {
                            put("type", "function_call_output"); put("call_id", m.toolCallId.orEmpty()); put("output", m.content.orEmpty())
                        })
                    }
                }
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function"); put("name", t.function.name); put("description", t.function.description)
                        put("parameters", t.function.parameters)
                    })
                }
            })
            if (cfg.reasoningEffort.isNotBlank()) putJsonObject("reasoning") { put("effort", cfg.reasoningEffort) }
        }

        /** A finished `function_call` output item → [ToolCall]. */
        fun functionCall(item: JsonObject?): ToolCall? {
            if (item?.get("type")?.jsonPrimitive?.contentOrNull != "function_call") return null
            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
            return ToolCall(
                id = item["call_id"]?.jsonPrimitive?.contentOrNull ?: "call_${System.nanoTime()}",
                function = FunctionCall(name, item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"),
            )
        }
    }
}
