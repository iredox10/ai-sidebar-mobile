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
    val model: String = "gpt-4o-mini"
)

data class RemoteChatMessage(val role: String, val content: String, val imageDataUrl: String? = null)

/**
 * Small dependency-free SSE client for the OpenAI chat-completions protocol.
 * Network work happens off the main thread; callbacks are returned on the main thread.
 */
class OpenAiCompatibleClient {
    fun streamChat(
        config: ProviderConfig,
        messages: List<RemoteChatMessage>,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
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
                    put("messages", JSONArray().apply {
                        messages.forEach { message ->
                            put(JSONObject().put("role", message.role).put("content", message.contentPayload()))
                        }
                    })
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                if (connection.responseCode !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    throw IllegalStateException("Request failed (${connection.responseCode}): ${detail.take(300)}")
                }
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (request.cancelled || line == "data: [DONE]") return@forEach
                        if (!line.startsWith("data: ")) return@forEach
                        val content = runCatching {
                            JSONObject(line.removePrefix("data: "))
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content")
                        }.getOrNull()
                        if (!content.isNullOrEmpty()) mainThread { onDelta(content) }
                    }
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
