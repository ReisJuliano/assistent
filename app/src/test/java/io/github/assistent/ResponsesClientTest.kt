package io.github.assistent

import io.github.assistent.brain.ChatMessage
import io.github.assistent.brain.FunctionCall
import io.github.assistent.brain.FunctionSpec
import io.github.assistent.brain.ResponsesClient
import io.github.assistent.brain.ToolCall
import io.github.assistent.brain.ToolSpec
import io.github.assistent.config.LlmConfig
import io.github.assistent.core.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ResponsesClientTest {
    private val cfg = LlmConfig(provider = "chatgpt", model = "gpt-6.1-sol", reasoningEffort = "low")

    @Test fun translatesHistoryAndToolsToResponsesShape() {
        val history = listOf(
            ChatMessage("system", "You are Nexo."),
            ChatMessage("user", "clima?"),
            ChatMessage("assistant", null, toolCalls = listOf(ToolCall("call_1", function = FunctionCall("get_weather", "{\"location\":\"SP\"}")))),
            ChatMessage("tool", "25 graus", toolCallId = "call_1"),
            ChatMessage("system", "late note"),
        )
        val tools = listOf(ToolSpec(function = FunctionSpec("get_weather", "weather", buildJsonObject { put("type", "object") })))
        val body = ResponsesClient.buildBody(cfg, history, tools)

        assertEquals("You are Nexo.", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", null, null, "developer"), input.map { it["role"]?.jsonPrimitive?.content })
        assertEquals("function_call", input[1]["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[2]["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[2]["type"]!!.jsonPrimitive.content)
        // No system items and no sampling parameters on plan tokens.
        assertFalse(input.any { it["role"]?.jsonPrimitive?.content == "system" })
        assertNull(body["temperature"])
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)
    }

    @Test fun parsesFinishedFunctionCallItems() {
        val item = AppJson.parseToJsonElement("""{"type":"function_call","call_id":"c9","name":"web_search","arguments":"{\"q\":\"x\"}"}""") as JsonObject
        val call = ResponsesClient.functionCall(item)!!
        assertEquals("c9", call.id)
        assertEquals("web_search", call.function.name)
        assertNull(ResponsesClient.functionCall(AppJson.parseToJsonElement("""{"type":"message"}""") as JsonObject))
    }
}
