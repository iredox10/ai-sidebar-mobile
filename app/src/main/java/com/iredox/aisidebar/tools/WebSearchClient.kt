package com.iredox.aisidebar.tools

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class SearchResult(val title: String, val snippet: String, val url: String)

/** Web search with Tavily if key supplied, else DuckDuckGo instant-answer. Results are returned for user review, never sent automatically. */
class WebSearchClient {
    fun search(query: String, tavilyKey: String? = null, onSuccess: (List<SearchResult>) -> Unit, onError: (String) -> Unit) {
        if (!tavilyKey.isNullOrBlank()) {
            searchTavily(query, tavilyKey, onSuccess, onError); return
        }
        searchDuckDuckGo(query, onSuccess, onError)
    }

    private fun searchTavily(query: String, apiKey: String, onSuccess: (List<SearchResult>) -> Unit, onError: (String) -> Unit) {
        Thread {
            runCatching {
                val conn = (URL("https://api.tavily.com/search").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 15_000; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().apply {
                    put("api_key", apiKey); put("query", query); put("max_results", 5); put("search_depth", "basic"); put("include_answer", true); put("include_raw_content", false)
                }
                conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
                if (conn.responseCode !in 200..299) {
                    val detail = conn.errorStream?.bufferedReader()?.readText()?.let { runCatching { JSONObject(it).optString("message", it) }.getOrNull() } ?: "Tavily error ${conn.responseCode}"
                    error(detail)
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText()).also { conn.disconnect() }
                buildList {
                    val answer = json.optString("answer")
                    if (answer.isNotBlank()) add(SearchResult("Answer", answer.take(500), ""))
                    json.optJSONArray("results")?.let { arr ->
                        for (i in 0 until minOf(arr.length(), 5)) {
                            val r = arr.optJSONObject(i) ?: continue
                            add(SearchResult(r.optString("title", query), r.optString("content", "").take(500), r.optString("url", "")))
                        }
                    }
                }.take(6).also { require(it.isNotEmpty()) { "Tavily returned no results" } }
            }.onSuccess { results -> main { onSuccess(results) } }
             .onFailure { e -> if ((e.message ?: "").contains("Tavily")) searchDuckDuckGo(query, onSuccess, onError) else main { onError(e.message ?: "Web search failed.") } }
        }.apply { name = "ai-sidebar-tavily"; start() }
    }

    private fun searchDuckDuckGo(query: String, onSuccess: (List<SearchResult>) -> Unit, onError: (String) -> Unit) {
        Thread {
            runCatching {
                val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                val connection = (URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000; readTimeout = 12_000; setRequestProperty("User-Agent", "AI-Sidebar-Mobile/0.1")
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val json = JSONObject(reader.readText())
                    buildList {
                        val answer = json.optString("AbstractText")
                        val answerUrl = json.optString("AbstractURL")
                        if (answer.isNotBlank()) add(SearchResult(json.optString("Heading", query), answer, answerUrl))
                        collectRelated(json.optJSONArray("RelatedTopics"), this)
                    }.take(4)
                }.also { connection.disconnect() }
            }.onSuccess { results -> main { onSuccess(results) } }
                .onFailure { error -> main { onError(error.message ?: "Web search failed.") } }
        }.apply { name = "ai-sidebar-web-search"; start() }
    }

    private fun collectRelated(items: JSONArray?, target: MutableList<SearchResult>) {
        if (items == null || target.size >= 4) return
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            item.optJSONArray("Topics")?.let { collectRelated(it, target) }
            val text = item.optString("Text")
            if (text.isNotBlank() && target.size < 4) target += SearchResult(text.substringBefore(" - "), text, item.optString("FirstURL"))
        }
    }

    private fun main(action: () -> Unit) = Handler(Looper.getMainLooper()).post(action)
}
