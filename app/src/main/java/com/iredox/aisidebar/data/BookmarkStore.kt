package com.iredox.aisidebar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val id: Long, val title: String, val url: String, val createdAt: Long)

class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun load(): List<Bookmark> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(Bookmark(o.optLong("id"), o.optString("title"), o.optString("url"), o.optLong("createdAt")))
            }
        }
    }.getOrDefault(emptyList())

    fun add(title: String, url: String) {
        val list = load().toMutableList()
        list.add(Bookmark(System.currentTimeMillis(), title.take(80), url, System.currentTimeMillis()))
        save(list)
    }

    private fun save(list: List<Bookmark>) {
        val arr = JSONArray().apply {
            list.takeLast(200).forEach { put(JSONObject().apply { put("id", it.id); put("title", it.title); put("url", it.url); put("createdAt", it.createdAt) }) }
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        const val PREFS = "bookmark_store"
        const val KEY = "bookmarks"
    }
}
