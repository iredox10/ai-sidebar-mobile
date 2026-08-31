package com.iredox.aisidebar.api

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streaming client for Anthropic Messages API, mirroring background.js streamAnthropic.
 */
class AnthropicClient {
    fun streamChat(
        config: ProviderConfig,
        messages: List<RemoteChatMessage>,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        onUsage: ((Long?, Long?) -> Unit)? = null
    ): StreamingRequest {
        val request = StreamingRequest()
        Thread {
            try {
                val connection = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).also {
                    request.connection = it
                    it.requestMethod = "POST"
                    it.connectTimeout = 20_000
                    it.readTimeout = 90_000
                    it.doOutput = true
                    it.setRequestProperty("Content-Type", "application/json")
                    it.setRequestProperty("x-api-key", config.apiKey)
                    it.setRequestProperty("anthropic-version", "2023-06-01")
                    it.setRequestProperty("Accept", "text/event-stream")
                }
                val body = JSONObject().apply {
                    put("model", config.model)
                    if (!config.systemPrompt.isNullOrBlank()) put("system", config.systemPrompt)
                    put("max_tokens", 4096)
                    config.temperature?.let { put("temperature", it) }
                    put("stream", true)
                    put("messages", JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().apply {
                                put("role", m.role)
                                put("content", m.anthropicContent())
                            })
                        }
                    })
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                if (connection.responseCode !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.readText()?.let { runCatching { JSONObject(it).optJSONObject("error")?.optString("message") ?: it }.getOrNull() } ?: "HTTP ${connection.responseCode}"
                    throw IllegalStateException(detail.take(600))
                }
                var promptTok: Long? = null
                var completionTok: Long? = null
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        if (request.cancelled) return@forEach
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith(":")) return@forEach
                        if (!line.startsWith("data:")) return@forEach
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") return@forEach
                        runCatching { JSONObject(payload) }.getOrNull()?.let { json ->
                            if (json.optString("type") == "message_delta") {
                                json.optJSONObject("usage")?.let {
                                    promptTok = it.optLong("input_tokens", -1).takeIf { v -> v >= 0 }
                                    completionTok = it.optLong("output_tokens", -1).takeIf { v -> v >= 0 }
                                }
                            }
                        }
                        val text = parseAnthropicDelta(payload) ?: return@forEach
                        if (text.isNotEmpty()) mainThread { onDelta(text) }
                    }
                }
                if (promptTok != null || completionTok != null) mainThread { onUsage?.invoke(promptTok, completionTok) }
                if (!request.cancelled) mainThread(onComplete)
            } catch (e: Exception) {
                if (!request.cancelled) mainThread { onError(e.message ?: "Anthropic request failed") }
            } finally {
                request.connection?.disconnect()
            }
        }.apply { name = "ai-sidebar-anthropic"; start() }
        return request
    }

    private fun parseAnthropicDelta(payload: String): String? = runCatching {
        val json = JSONObject(payload)
        when (json.optString("type")) {
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return null
                if (delta.optString("type") == "text_delta") delta.optString("text").takeIf { it.isNotEmpty() } else null
            }
            else -> null
        }
    }.getOrNull()

    private fun mainThread(action: () -> Unit) = Handler(Looper.getMainLooper()).post(action)
}

private fun RemoteChatMessage.anthropicContent(): Any {
    val imgs = allImages()
    if (imgs.isNotEmpty()) {
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", content))
            imgs.forEach { imageDataUrl ->
                val comma = imageDataUrl.indexOf(',')
                val header = if (comma > 0) imageDataUrl.substring(0, comma) else ""
                val data = if (comma > 0) imageDataUrl.substring(comma + 1) else imageDataUrl
                val mime = Regex("data:(.*?);").find(header)?.groupValues?.get(1) ?: "image/jpeg"
                put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", data)))
            }
        }
    }
    return content
}
