package com.iredox.aisidebar.api

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ProviderConfig(
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String,
    val model: String = "gpt-4o-mini",
    val systemPrompt: String? = null,
    val temperature: Double? = 0.7
)

data class RemoteChatMessage(val role: String, val content: String, val imageDataUrl: String? = null)

/**
 * Small dependency-free SSE client for the OpenAI chat-completions protocol.
 * Network work happens off the main thread; callbacks are returned on the main thread.
 */
class OpenAiCompatibleClient {
    fun listModels(config: ProviderConfig, onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) {
        Thread {
            runCatching {
                val modelsEndpoint = config.endpoint.replace(Regex("/chat/completions/?$"), "/models")
                val connection = (URL(modelsEndpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) {
                    error("Request failed (${connection.responseCode}). Check the endpoint and API key.")
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val data = JSONObject(reader.readText()).optJSONArray("data") ?: JSONArray()
                    buildList {
                        for (index in 0 until data.length()) {
                            data.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
                        }
                    }.distinct().sorted()
                }.also { connection.disconnect() }
            }.onSuccess { models -> mainThread { onSuccess(models) } }
                .onFailure { error -> mainThread { onError(error.message ?: "Could not load models.") } }
        }.apply { name = "ai-sidebar-model-list"; start() }
    }

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
                val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).also {
                    request.connection = it
                    it.requestMethod = "POST"
                    it.connectTimeout = 20_000
                    it.readTimeout = 90_000
                    it.doOutput = true
                    it.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    it.setRequestProperty("Content-Type", "application/json")
                    it.setRequestProperty("Accept", "text/event-stream")
                }
                val body = JSONObject().apply {
                    put("model", config.model)
                    put("stream", true)
                    config.temperature?.let { put("temperature", it) }
                    put("stream_options", JSONObject().put("include_usage", true))
                    put("messages", JSONArray().apply {
                        if (!config.systemPrompt.isNullOrBlank()) {
                            put(JSONObject().put("role", "system").put("content", config.systemPrompt))
                        }
                        messages.forEach { message ->
                            put(JSONObject().put("role", message.role).put("content", message.contentPayload()))
                        }
                    })
                }
                if (config.endpoint.contains("openrouter")) {
                    connection.setRequestProperty("HTTP-Referer", "https://github.com/ai-sidebar")
                    connection.setRequestProperty("X-Title", "AI Sidebar")
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                if (connection.responseCode !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.readText()?.let { runCatching { JSONObject(it).optJSONObject("error")?.optString("message") ?: it }.getOrNull() }?.take(600)
                    throw IllegalStateException(detail ?: "Request failed (${connection.responseCode}). Check the endpoint, model, and API key.")
                }
                var promptTok: Long? = null
                var completionTok: Long? = null
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (request.cancelled || line == "data: [DONE]") return@forEach
                        if (!line.startsWith("data: ")) return@forEach
                        val json = runCatching { JSONObject(line.removePrefix("data: ")) }.getOrNull() ?: return@forEach
                        json.optJSONObject("usage")?.let {
                            promptTok = it.optLong("prompt_tokens", -1).takeIf { v -> v >= 0 }
                            completionTok = it.optLong("completion_tokens", -1).takeIf { v -> v >= 0 }
                        }
                        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val content = delta?.optString("content")?.takeIf { it.isNotEmpty() }
                            ?: delta?.optString("reasoning_content")?.takeIf { it.isNotEmpty() }
                        if (!content.isNullOrEmpty()) mainThread { onDelta(content) }
                    }
                }
                if (promptTok != null || completionTok != null) {
                    val pt = promptTok; val ct = completionTok
                    mainThread { onUsage?.invoke(pt, ct) }
                }
                if (!request.cancelled) mainThread(onComplete)
            } catch (error: Exception) {
                if (!request.cancelled) mainThread { onError(error.message ?: "Could not connect to the provider.") }
            } finally {
                request.connection?.disconnect()
            }
        }.apply { name = "ai-sidebar-stream"; start() }
        return request
    }

    private fun mainThread(action: () -> Unit) = Handler(Looper.getMainLooper()).post(action)
}

private fun RemoteChatMessage.contentPayload(): Any = imageDataUrl?.let { dataUrl ->
    JSONArray().put(JSONObject().put("type", "text").put("text", content))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
} ?: content

class StreamingRequest internal constructor() {
    @Volatile internal var connection: HttpURLConnection? = null
    @Volatile internal var cancelled = false

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }
}
