package io.github.assistent.tools

import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.LlmClient
import io.github.assistent.config.ConfigStore
import io.github.assistent.config.LlmConfig
import io.github.assistent.core.Activity
import io.github.assistent.core.AssistantRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Describes what the camera currently sees using a vision-language model.
 * Uses the Gemini free-tier endpoint when `google.apiKey` is set, otherwise falls back to
 * the main LLM (works if the configured model supports vision, e.g. gpt-4o).
 */
class LookTool(
    private val runtime: AssistantRuntime,
    private val config: ConfigStore,
    private val llm: LlmClient,
) : Tool {
    override val name = "look"
    override val description = "Describe or read what the camera currently sees. " +
        "Use for 'o que é isso', reading text/labels, describing people, objects or the room."
    override val activity = Activity.SEARCHING
    override val parameters = params {
        string("question", "What to look for or describe; leave empty for a full scene description", required = false)
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (runtime.cameraBrightness < 28f) return@withContext "LOW_LIGHT: imagem muito escura para ver"
        val frame = runtime.frameBase64() ?: return@withContext "CAMERA_UNAVAILABLE: câmera indisponível"
        val question = args.str("question")?.trim()?.ifBlank { null }
            ?: "Descreva detalhadamente o que você está vendo nesta imagem."
        val cfg = config.current
        val visionCfg = if (cfg.google.apiKey.isNotBlank()) {
            LlmConfig(
                provider = "proxy",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                apiKey = cfg.google.apiKey,
                model = cfg.google.visionModel,
            )
        } else {
            cfg.llm
        }
        val result = withTimeoutOrNull(10_000) {
            runCatching {
                llm.complete(
                    visionCfg,
                    listOf(ChatMessage("user", question, imageBase64 = frame)),
                    emptyList(),
                ).content.orEmpty().trim()
            }.getOrElse { "VISION_ERROR: ${it.message}" }
        } ?: "TIMEOUT: sem resposta da visão em 10 s"
        result
    }
}
