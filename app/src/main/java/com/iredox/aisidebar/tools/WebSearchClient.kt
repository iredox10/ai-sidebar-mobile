package com.iredox.aisidebar.tools

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class SearchResult(val title: String, val snippet: String, val url: String)

/** Explicit DuckDuckGo instant-answer lookup. Results are returned for user review, never sent automatically. */
class WebSearchClient {
    fun search(query: String, onSuccess: (List<SearchResult>) -> Unit, onError: (String) -> Unit) {
        Thread {
            runCatching {
                val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                val connection = (URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 12_000
                    setRequestProperty("User-Agent", "AI-Sidebar-Mobile/0.1")
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
