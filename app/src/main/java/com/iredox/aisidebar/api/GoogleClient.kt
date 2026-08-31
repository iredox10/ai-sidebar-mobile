package com.iredox.aisidebar.api

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Streaming client for Google Generative Language API, mirroring background.js streamGoogle.
 */
class GoogleClient {
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
                val encodedModel = URLEncoder.encode(config.model, "UTF-8")
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${encodedModel}:streamGenerateContent?alt=sse&key=${URLEncoder.encode(config.apiKey, "UTF-8")}")
                val connection = (url.openConnection() as HttpURLConnection).also {
                    request.connection = it
                    it.requestMethod = "POST"
                    it.connectTimeout = 20_000
                    it.readTimeout = 90_000
                    it.doOutput = true
                    it.setRequestProperty("Content-Type", "application/json")
                    it.setRequestProperty("Accept", "text/event-stream")
                }
                val contents = JSONArray().apply {
                    messages.forEach { m ->
                        val role = if (m.role == "assistant") "model" else "user"
                        put(JSONObject().apply {
                            put("role", role)
                            put("parts", m.googleParts())
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("contents", contents)
                    if (!config.systemPrompt.isNullOrBlank()) {
                        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", config.systemPrompt))))
                    }
                    val gen = JSONObject()
                    config.temperature?.let { gen.put("temperature", it) }
                    if (gen.length() > 0) put("generationConfig", gen)
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
                            json.optJSONObject("usageMetadata")?.let {
                                promptTok = it.optLong("promptTokenCount", -1).takeIf { v -> v >= 0 }
                                completionTok = it.optLong("candidatesTokenCount", -1).takeIf { v -> v >= 0 }
                            }
                        }
                        val texts = parseGoogleDelta(payload)
                        texts.forEach { t -> if (t.isNotEmpty()) mainThread { onDelta(t) } }
                    }
                }
                if (promptTok != null || completionTok != null) mainThread { onUsage?.invoke(promptTok, completionTok) }
                if (!request.cancelled) mainThread(onComplete)
            } catch (e: Exception) {
                if (!request.cancelled) mainThread { onError(e.message ?: "Google request failed") }
            } finally {
                request.connection?.disconnect()
            }
        }.apply { name = "ai-sidebar-google"; start() }
        return request
    }

    private fun parseGoogleDelta(payload: String): List<String> = runCatching {
        val json = JSONObject(payload)
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return emptyList()
        buildList {
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text")
                if (!text.isNullOrEmpty()) add(text)
            }
        }
    }.getOrDefault(emptyList())

    private fun mainThread(action: () -> Unit) = Handler(Looper.getMainLooper()).post(action)
}

private fun RemoteChatMessage.googleParts(): JSONArray {
    val imgs = allImages()
    if (imgs.isNotEmpty()) {
        return JSONArray().apply {
            put(JSONObject().put("text", content))
            imgs.forEach { imageDataUrl ->
                val comma = imageDataUrl.indexOf(',')
                val header = if (comma > 0) imageDataUrl.substring(0, comma) else ""
                val data = if (comma > 0) imageDataUrl.substring(comma + 1) else imageDataUrl
                val mime = Regex("data:(.*?);").find(header)?.groupValues?.get(1) ?: "image/jpeg"
                put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data)))
            }
        }
    }
    return JSONArray().put(JSONObject().put("text", content))
}
