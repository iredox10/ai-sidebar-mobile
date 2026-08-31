package com.iredox.aisidebar.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.iredox.aisidebar.screen.ScreenReadAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class ToolDefinition(val name: String, val description: String, val parameters: JSONObject)

val AGENTIC_TOOLS: List<ToolDefinition> = listOf(
    ToolDefinition("web_search", "Search the web for current information. Use for recent events, news, live data.", JSONObject().apply {
        put("type", "object"); put("properties", JSONObject().apply {
            put("query", JSONObject().put("type", "string").put("description", "The search query"))
            put("maxResults", JSONObject().put("type", "integer").put("description", "1-5").put("minimum", 1).put("maximum", 5))
        }); put("required", JSONArray().put("query"))
    }),
    ToolDefinition("fetch_url", "Fetch readable text content of a web page.", JSONObject().apply {
        put("type", "object"); put("properties", JSONObject().apply {
            put("url", JSONObject().put("type", "string").put("description", "Full URL including https://"))
        }); put("required", JSONArray().put("url"))
    }),
    ToolDefinition("get_page", "Get the text content of the page the user is currently viewing.", JSONObject().apply {
        put("type", "object"); put("properties", JSONObject())
    }),
    ToolDefinition("open_url", "Open a URL in the browser.", JSONObject().apply {
        put("type", "object"); put("properties", JSONObject().apply {
            put("url", JSONObject().put("type", "string"))
        }); put("required", JSONArray().put("url"))
    }),
    ToolDefinition("current_date", "Get current date and time.", JSONObject().apply {
        put("type", "object"); put("properties", JSONObject())
    }),
    ToolDefinition("create_bookmark", "Save a bookmark for the user.", JSONObject().apply {
        put("type", "object"); put("properties", JSONObject().apply {
            put("title", JSONObject().put("type", "string")); put("url", JSONObject().put("type", "string"))
        }); put("required", JSONArray().put("title").put("url"))
    }),
    ToolDefinition("download_file", "Trigger a file download with text content.", JSONObject().apply {
        put("type", "object"); put("properties", JSONObject().apply {
            put("filename", JSONObject().put("type", "string")); put("content", JSONObject().put("type", "string"))
        }); put("required", JSONArray().put("filename").put("content"))
    })
)

data class ToolCall(val id: String, val name: String, val arguments: String)

fun toolDefinitionsForOpenAI(): JSONArray = JSONArray().apply {
    AGENTIC_TOOLS.forEach { t ->
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", t.name); put("description", t.description); put("parameters", t.parameters)
            })
        })
    }
}

fun runToolSync(context: Context?, call: ToolCall, tavilyKey: String?): String {
    val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
    return try {
        when (call.name) {
            "web_search" -> {
                val query = args.optString("query").trim()
                if (query.isEmpty()) "No query provided"
                else {
                    // synchronous Tavily or DuckDuckGo
                    val key = tavilyKey
                    if (!key.isNullOrBlank()) {
                        // Tavily sync
                        val conn = (URL("https://api.tavily.com/search").openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 15000; doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        val body = JSONObject().apply { put("api_key", key); put("query", query); put("max_results", args.optInt("maxResults", 5)); put("search_depth", "basic"); put("include_answer", true) }
                        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                        if (conn.responseCode !in 200..299) "Tavily error ${conn.responseCode}"
                        else {
                            val json = JSONObject(conn.inputStream.bufferedReader().readText())
                            val sb = StringBuilder()
                            json.optString("answer")?.takeIf { it.isNotBlank() }?.let { sb.append("Answer: $it\n\n") }
                            json.optJSONArray("results")?.let { arr ->
                                for (i in 0 until minOf(arr.length(), 5)) {
                                    val r = arr.optJSONObject(i) ?: continue
                                    sb.append("- ${r.optString("title")}\n  ${r.optString("url")}\n  ${r.optString("content").take(400)}\n")
                                }
                            }
                            sb.toString().take(6000).ifBlank { "No results" }
                        }
                    } else {
                        // DuckDuckGo sync fallback
                        val enc = java.net.URLEncoder.encode(query, "UTF-8")
                        val conn = (URL("https://api.duckduckgo.com/?q=$enc&format=json&no_html=1&skip_disambig=1").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 12000; readTimeout = 12000
                        }
                        val json = JSONObject(conn.inputStream.bufferedReader().readText())
                        val sb = StringBuilder()
                        json.optString("AbstractText")?.takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n\n") }
                        json.optJSONArray("RelatedTopics")?.let { arr ->
                            for (i in 0 until minOf(arr.length(), 4)) {
                                val o = arr.optJSONObject(i) ?: continue
                                val text = o.optString("Text")
                                if (text.isNotBlank()) sb.append("- $text\n  ${o.optString("FirstURL")}\n")
                            }
                        }
                        sb.toString().take(6000).ifBlank { "No DuckDuckGo results" }
                    }
                }
            }
            "fetch_url" -> {
                val url = args.optString("url").trim()
                if (!url.startsWith("http")) "Invalid URL"
                else {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 15000; setRequestProperty("User-Agent", "AI-Sidebar-Mobile/0.1") }
                    if (conn.responseCode !in 200..299) "HTTP ${conn.responseCode}"
                    else {
                        val ct = conn.contentType ?: ""
                        if (ct.contains("application/pdf") || url.lowercase().contains(".pdf")) "PDF fetch not supported via tool — advise user to attach PDF directly."
                        else {
                            val raw = conn.inputStream.bufferedReader().readText().take(100000)
                            raw.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                                .replace(Regex("<[^>]+>"), " ")
                                .replace(Regex("\\s+"), " ").trim().take(12000).ifBlank { "No readable text" }
                        }
                    }
                }
            }
            "get_page" -> {
                val capture = ScreenReadAccessibilityService.captureActiveScreen()
                if (capture?.visibleText.isNullOrBlank()) "No visible page text available (enable accessibility or no text on screen)."
                else "Page (${capture.packageName}):\n${capture.visibleText.take(8000)}"
            }
            "open_url" -> {
                val url = args.optString("url").trim()
                if (!url.startsWith("http")) "Invalid URL"
                else {
                    try {
                        val ctx = context
                        if (ctx != null) {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                            "Opened $url"
                        } else "Would open $url (no context)"
                    } catch (e: Exception) { "Failed to open: ${e.message}" }
                }
            }
            "current_date" -> SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            "create_bookmark" -> {
                val title = args.optString("title", "Bookmark")
                val url = args.optString("url")
                if (url.isBlank()) "Missing url"
                else {
                    context?.let { com.iredox.aisidebar.data.BookmarkStore(it).add(title, url) }
                    "Created bookmark \"$title\" -> $url"
                }
            }
            "download_file" -> {
                val filename = args.optString("filename", "export.txt").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val content = args.optString("content", "")
                if (context == null) "Download not supported without context"
                else {
                    try {
                        val resolver = context.contentResolver
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create download")
                        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                        values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        "Download started: $filename"
                    } catch (e: Exception) { "Download failed: ${e.message}" }
                }
            }
            else -> "Unknown tool ${call.name}"
        }
    } catch (e: Exception) { "Tool error: ${e.message}" }
}
