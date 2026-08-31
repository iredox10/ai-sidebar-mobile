package com.iredox.aisidebar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredChatMessage(val id: Long, val role: String, val text: String)

/** Lightweight local persistence for the active conversation. Room replaces this in the history milestone. */
class ChatStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadMessages(): List<StoredChatMessage> = runCatching {
        val raw = preferences.getString(MESSAGES_KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(StoredChatMessage(item.getLong("id"), item.getString("role"), item.getString("text")))
            }
        }
    }.getOrDefault(emptyList())

    fun saveMessages(messages: List<StoredChatMessage>) {
        val encoded = JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().put("id", message.id).put("role", message.role).put("text", message.text))
            }
        }
        preferences.edit().putString(MESSAGES_KEY, encoded.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "conversation_cache"
        const val MESSAGES_KEY = "active_messages"
    }
}
