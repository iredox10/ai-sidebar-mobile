package com.iredox.aisidebar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredChatMessage(val id: Long, val role: String, val text: String)
data class StoredConversation(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredChatMessage>
)

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

    fun loadHistory(): List<StoredConversation> = runCatching {
        val raw = preferences.getString(HISTORY_KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val messages = item.getJSONArray("messages")
                add(
                    StoredConversation(
                        id = item.getLong("id"),
                        title = item.getString("title"),
                        updatedAt = item.getLong("updatedAt"),
                        messages = buildList {
                            for (messageIndex in 0 until messages.length()) {
                                val message = messages.getJSONObject(messageIndex)
                                add(StoredChatMessage(message.getLong("id"), message.getString("role"), message.getString("text")))
                            }
                        }
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun saveConversation(conversation: StoredConversation) {
        val conversations = loadHistory().filterNot { it.id == conversation.id }.toMutableList()
        conversations += conversation
        val encoded = JSONArray().apply {
            conversations.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS).forEach { saved ->
                put(JSONObject().apply {
                    put("id", saved.id)
                    put("title", saved.title)
                    put("updatedAt", saved.updatedAt)
                    put("messages", JSONArray().apply {
                        saved.messages.forEach { message -> put(JSONObject().put("id", message.id).put("role", message.role).put("text", message.text)) }
                    })
                })
            }
        }
        preferences.edit().putString(HISTORY_KEY, encoded.toString()).apply()
    }

    fun deleteConversation(id: Long) {
        val remaining = loadHistory().filterNot { it.id == id }
        val encoded = JSONArray().apply {
            remaining.forEach { saved ->
                put(JSONObject().apply {
                    put("id", saved.id)
                    put("title", saved.title)
                    put("updatedAt", saved.updatedAt)
                    put("messages", JSONArray().apply {
                        saved.messages.forEach { message -> put(JSONObject().put("id", message.id).put("role", message.role).put("text", message.text)) }
                    })
                })
            }
        }
        preferences.edit().putString(HISTORY_KEY, encoded.toString()).apply()
    }

    fun renameConversation(id: Long, title: String) {
        val existing = loadHistory().firstOrNull { it.id == id } ?: return
        val cleanTitle = title.trim().takeIf { it.isNotEmpty() } ?: return
        saveConversation(existing.copy(title = cleanTitle, updatedAt = System.currentTimeMillis()))
    }

    fun exportHistory(): String = JSONObject().apply {
        put("format", "ai-sidebar-mobile")
        put("version", 1)
        put("exportedAt", System.currentTimeMillis())
        put("conversations", JSONArray().apply {
            loadHistory().forEach { saved ->
                put(JSONObject().apply {
                    put("id", saved.id)
                    put("title", saved.title)
                    put("updatedAt", saved.updatedAt)
                    put("messages", JSONArray().apply {
                        saved.messages.forEach { message -> put(JSONObject().put("id", message.id).put("role", message.role).put("text", message.text)) }
                    })
                })
            }
        })
    }.toString(2)

    fun activeConversationId(): Long? = preferences.getLong(ACTIVE_ID_KEY, NO_CONVERSATION).takeIf { it != NO_CONVERSATION }

    fun setActiveConversationId(id: Long) {
        preferences.edit().putLong(ACTIVE_ID_KEY, id).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "conversation_cache"
        const val MESSAGES_KEY = "active_messages"
        const val HISTORY_KEY = "conversation_history"
        const val ACTIVE_ID_KEY = "active_conversation_id"
        const val NO_CONVERSATION = -1L
        const val MAX_CONVERSATIONS = 50
    }
}
