package com.iredox.aisidebar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredChatMessage(val id: Long, val role: String, val text: String)
data class StoredConversation(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredChatMessage>,
    val pinned: Boolean = false,
    val provider: String = "",
    val model: String = "",
    val url: String = "",
    val domain: String = "",
    val pageTitle: String = ""
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
                        pinned = item.optBoolean("pinned", false),
                        provider = item.optString("provider", ""),
                        model = item.optString("model", ""),
                        url = item.optString("url", ""),
                        domain = item.optString("domain", ""),
                        pageTitle = item.optString("pageTitle", ""),
                        messages = buildList {
                            for (messageIndex in 0 until messages.length()) {
                                val message = messages.getJSONObject(messageIndex)
                                add(StoredChatMessage(message.getLong("id"), message.getString("role"), message.getString("text")))
                            }
                        }
                    )
                )
            }
        }.sortedWith(compareByDescending<StoredConversation> { it.pinned }.thenByDescending { it.updatedAt })
    }.getOrDefault(emptyList())

    private fun persistHistory(conversations: List<StoredConversation>) {
        val encoded = JSONArray().apply {
            conversations.sortedWith(compareByDescending<StoredConversation> { it.pinned }.thenByDescending { it.updatedAt }).take(MAX_CONVERSATIONS).forEach { saved ->
                put(JSONObject().apply {
                    put("id", saved.id)
                    put("title", saved.title)
                    put("updatedAt", saved.updatedAt)
                    put("pinned", saved.pinned)
                    put("provider", saved.provider)
                    put("model", saved.model)
                    put("url", saved.url)
                    put("domain", saved.domain)
                    put("pageTitle", saved.pageTitle)
                    put("messages", JSONArray().apply {
                        saved.messages.forEach { message -> put(JSONObject().put("id", message.id).put("role", message.role).put("text", message.text)) }
                    })
                })
            }
        }
        preferences.edit().putString(HISTORY_KEY, encoded.toString()).apply()
    }

    fun saveConversation(conversation: StoredConversation) {
        val conversations = loadHistory().filterNot { it.id == conversation.id }.toMutableList()
        conversations += conversation
        persistHistory(conversations)
    }

    fun deleteConversation(id: Long) {
        val remaining = loadHistory().filterNot { it.id == id }
        persistHistory(remaining)
    }

    fun renameConversation(id: Long, title: String) {
        val existing = loadHistory().firstOrNull { it.id == id } ?: return
        val cleanTitle = title.trim().takeIf { it.isNotEmpty() } ?: return
        saveConversation(existing.copy(title = cleanTitle, updatedAt = System.currentTimeMillis()))
    }

    fun togglePin(id: Long) {
        val existing = loadHistory().firstOrNull { it.id == id } ?: return
        saveConversation(existing.copy(pinned = !existing.pinned, updatedAt = System.currentTimeMillis()))
    }

    fun updateMetadata(id: Long, provider: String, model: String) {
        val existing = loadHistory().firstOrNull { it.id == id } ?: return
        if (existing.provider == provider && existing.model == model) return
        saveConversation(existing.copy(provider = provider, model = model))
    }

    fun exportHistory(): String = JSONObject().apply {
        put("format", "ai-sidebar-mobile")
        put("version", 2)
        put("exportedAt", System.currentTimeMillis())
        put("conversations", JSONArray().apply {
            loadHistory().forEach { saved ->
                put(JSONObject().apply {
                    put("id", saved.id)
                    put("title", saved.title)
                    put("updatedAt", saved.updatedAt)
                    put("pinned", saved.pinned)
                    put("provider", saved.provider)
                    put("model", saved.model)
                    put("url", saved.url)
                    put("domain", saved.domain)
                    put("pageTitle", saved.pageTitle)
                    put("messages", JSONArray().apply {
                        saved.messages.forEach { message -> put(JSONObject().put("id", message.id).put("role", message.role).put("text", message.text)) }
                    })
                })
            }
        })
    }.toString(2)

    fun importHistory(raw: String): Int {
        val root = JSONObject(raw)
        require(root.optString("format") == "ai-sidebar-mobile") { "This is not an AI Sidebar Mobile backup." }
        val conversations = root.optJSONArray("conversations") ?: return 0
        var imported = 0
        for (index in 0 until conversations.length()) {
            val item = conversations.optJSONObject(index) ?: continue
            val messages = item.optJSONArray("messages") ?: continue
            val savedMessages = buildList {
                for (messageIndex in 0 until messages.length()) {
                    val message = messages.optJSONObject(messageIndex)
                    if (message != null) {
                        val role = message.optString("role")
                        if (role == "USER" || role == "ASSISTANT") {
                            add(StoredChatMessage(message.optLong("id", System.currentTimeMillis()), role, message.optString("text")))
                        }
                    }
                }
            }
            if (savedMessages.isNotEmpty()) {
                saveConversation(
                    StoredConversation(
                        id = item.optLong("id", System.currentTimeMillis() + index),
                        title = item.optString("title", "Imported conversation"),
                        updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                        pinned = item.optBoolean("pinned", false),
                        provider = item.optString("provider", ""),
                        model = item.optString("model", ""),
                        url = item.optString("url", ""),
                        domain = item.optString("domain", ""),
                        pageTitle = item.optString("pageTitle", ""),
                        messages = savedMessages
                    )
                )
                imported++
            }
        }
        return imported
    }

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
