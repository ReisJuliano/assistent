package io.github.assistent.tools

import io.github.assistent.brain.FunctionSpec
import io.github.assistent.brain.ToolSpec
import io.github.assistent.core.Activity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Something the assistant can do. Add a new capability = implement this and register it in
 * [ToolRegistry]. Tools are also callable directly through `POST /api/v1/tools/{name}`.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    /** Face shown while the tool runs. */
    val activity: Activity get() = Activity.THINKING

    /** Returns text (often JSON) that goes back to the model. */
    suspend fun execute(args: JsonObject): String

    fun spec() = ToolSpec(function = FunctionSpec(name, description, parameters))
}

class ParamsBuilder {
    private val props = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = true, enum: List<String>? = null) =
        add(name, "string", description, required) { if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } }) }

    fun number(name: String, description: String, required: Boolean = true) = add(name, "number", description, required)
    fun integer(name: String, description: String, required: Boolean = true) = add(name, "integer", description, required)
    fun boolean(name: String, description: String, required: Boolean = true) = add(name, "boolean", description, required)

    private fun add(
        name: String, type: String, description: String, req: Boolean,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) {
        props[name] = buildJsonObject { put("type", type); put("description", description); extra() }
        if (req) required += name
    }

    fun build() = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
}

fun params(block: ParamsBuilder.() -> Unit = {}) = ParamsBuilder().apply(block).build()

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonElement.asText(): String = (this as? JsonPrimitive)?.content ?: toString()
