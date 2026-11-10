package io.github.assistent.live

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** Callback interface for LiveSession events. */
interface LiveSessionListener {
    fun onAudioChunk(pcm24k: ShortArray)
    fun onEmotionTag(emotion: String)
    fun onToolCall(id: String, name: String, args: JSONObject)
    fun onTurnComplete()
    fun onError(msg: String)
    fun onClosed()
}

/**
 * WebSocket session against Gemini Live API (BidiGenerateContent).
 * Sends 16 kHz mono PCM chunks; receives 24 kHz PCM audio + tool calls.
 */
class LiveSession(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val toolDeclarations: JSONArray,
    private val voice: String,
    private val listener: LiveSessionListener,
) {
    private var ws: WebSocket? = null
    fun connect(): Boolean {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url)
            .header("Content-Type", "application/json").build()
        ws = http.newWebSocket(request, Listener())
        return true
    }

    fun sendSetup() {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$model")
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().apply { put("AUDIO") })
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply { put("voice_name", voice) })
                        })
                    })
                })
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        add(JSONObject().apply { put("text", "$systemPrompt\nComece respostas com [emotion:feliz] ou similar.") })
                    })
                })
                if (toolDeclarations.length() > 0) {
                    put("tools", JSONArray().apply {
                        add(JSONObject().apply { put("function_declarations", toolDeclarations) })
                    })
                }
            })
        }
        ws?.send(setup.toString())
    }

    fun sendAudioChunk(pcm16k: ShortArray) {
        val bytes = ByteArray(pcm16k.size * 2)
        for (i in pcm16k.indices) {
            bytes[i * 2] = (pcm16k[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcm16k[i].toInt() shr 8 and 0xFF).toByte()
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    add(JSONObject().apply {
                        put("mime_type", "audio/pcm;rate=16000")
                        put("data", b64)
                    })
                })
            })
        }
        ws?.send(msg.toString())
    }

    fun sendToolResponse(callId: String, result: String) {
        val msg = JSONObject().apply {
            put("tool_response", JSONObject().apply {
                put("function_responses", JSONArray().apply {
                    add(JSONObject().apply {
                        put("id", callId)
                        put("name", "")
                        put("response", JSONObject().apply { put("output", result) })
                    })
                })
            })
        }
        ws?.send(msg.toString())
    }

    fun close() {
        ws?.close(1000, "done")
        ws = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i("LiveSession", "connected")
            sendSetup()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                // serverContent → audio or text
                json.optJSONObject("serverContent")?.let { content ->
                    content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            part.optJSONObject("inlineData")?.let { data ->
                                val mimeType = data.optString("mimeType", "")
                                if (mimeType.startsWith("audio/pcm")) {
                                    val bytes = Base64.decode(data.optString("data"), Base64.DEFAULT)
                                    val shorts = ShortArray(bytes.size / 2)
                                    for (j in shorts.indices) {
                                        shorts[j] = ((bytes[j * 2 + 1].toInt() shl 8) or (bytes[j * 2].toInt() and 0xFF)).toShort()
                                    }
                                    listener.onAudioChunk(shorts)
                                }
                            }
                            part.optString("text").takeIf { it.isNotBlank() }?.let { t ->
                                val emotionRegex = Regex("""\[emotion:(\w+)]""", RegexOption.IGNORE_CASE)
                                emotionRegex.find(t)?.groupValues?.get(1)?.let { listener.onEmotionTag(it) }
                            }
                        }
                    }
                    if (content.optBoolean("turnComplete")) listener.onTurnComplete()
                }
                // toolCall
                json.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                    for (i in 0 until calls.length()) {
                        val call = calls.getJSONObject(i)
                        listener.onToolCall(
                            call.optString("id"),
                            call.optString("name"),
                            call.optJSONObject("args") ?: JSONObject(),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("LiveSession", "parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener.onError(t.message ?: "WebSocket failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            listener.onClosed()
        }
    }
}

private fun JSONArray.add(obj: Any) = put(obj)
