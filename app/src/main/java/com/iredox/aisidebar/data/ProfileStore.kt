package com.iredox.aisidebar.data

import android.content.Context
import org.json.JSONObject

/**
 * Profiles bundle provider settings. Keys remain globally encrypted via SecureKeyStore
 * (per-profile encrypted keys would require namespaced Keystore entries — future increment).
 * Mirrors extension's profiles/activeProfile in chrome.storage.sync.
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val settingsStore = ProviderSettingsStore(context)

    fun load(): Pair<Map<String, ProviderSettings>, String> {
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw == null) {
            // migrate: create Default from current settings
            val current = settingsStore.read()
            val map = mapOf("Default" to current)
            save(map, "Default")
            return map to "Default"
        }
        return runCatching {
            val json = JSONObject(raw)
            val active = json.optString("activeProfile", "Default")
            val profilesObj = json.optJSONObject("profiles") ?: JSONObject()
            val map = mutableMapOf<String, ProviderSettings>()
            profilesObj.keys().forEach { name ->
                val o = profilesObj.optJSONObject(name) ?: return@forEach
                map[name] = ProviderSettings(
                    provider = o.optString("provider", "openai"),
                    endpoint = o.optString("endpoint", "https://api.openai.com/v1/chat/completions"),
                    model = o.optString("model", "gpt-4o-mini"),
                    systemPrompt = o.optString("systemPrompt", "You are a helpful assistant."),
                    temperature = o.optDouble("temperature", 0.7).toFloat(),
                    customName = o.optString("customName", ""),
                    customBaseUrl = o.optString("customBaseUrl", ""),
                    customModels = o.optString("customModels", ""),
                    searchEnabled = o.optBoolean("searchEnabled", false),
                    autoSearch = o.optBoolean("autoSearch", true),
                    agenticTools = o.optBoolean("agenticTools", true)
                )
            }
            val safeMap = if (map.isEmpty()) mapOf("Default" to settingsStore.read()) else map
            val safeActive = if (safeMap.containsKey(active)) active else safeMap.keys.first()
            safeMap to safeActive
        }.getOrDefault(mapOf("Default" to settingsStore.read()) to "Default")
    }

    fun save(profiles: Map<String, ProviderSettings>, activeProfile: String) {
        val json = JSONObject().apply {
            put("activeProfile", activeProfile)
            put("profiles", JSONObject().apply {
                profiles.forEach { (name, s) ->
                    put(name, JSONObject().apply {
                        put("provider", s.provider)
                        put("endpoint", s.endpoint)
                        put("model", s.model)
                        put("systemPrompt", s.systemPrompt)
                        put("temperature", s.temperature)
                        put("customName", s.customName)
                        put("customBaseUrl", s.customBaseUrl)
                        put("customModels", s.customModels)
                        put("searchEnabled", s.searchEnabled)
                        put("autoSearch", s.autoSearch)
                        put("agenticTools", s.agenticTools)
                    })
                }
            })
        }
        prefs.edit().putString(KEY_PROFILES, json.toString()).apply()
    }

    fun activate(name: String) {
        val (profiles, _) = load()
        val settings = profiles[name] ?: return
        settingsStore.write(settings)
        save(profiles, name)
    }

    fun create(name: String, settings: ProviderSettings) {
        val (profiles, active) = load()
        val newMap = profiles.toMutableMap()
        newMap[name] = settings
        save(newMap, name)
        settingsStore.write(settings)
    }

    fun duplicate(from: String, to: String) {
        val (profiles, _) = load()
        val src = profiles[from] ?: return
        val newMap = profiles.toMutableMap()
        newMap[to] = src
        save(newMap, to)
    }

    fun delete(name: String) {
        val (profiles, active) = load()
        if (profiles.size <= 1) return
        val newMap = profiles.toMutableMap()
        newMap.remove(name)
        val nextActive = if (active == name) newMap.keys.first() else active
        save(newMap, nextActive)
        newMap[nextActive]?.let { settingsStore.write(it) }
    }

    fun rename(old: String, new: String) {
        val (profiles, active) = load()
        if (!profiles.containsKey(old) || profiles.containsKey(new)) return
        val newMap = profiles.toMutableMap()
        newMap[new] = newMap.remove(old)!!
        val nextActive = if (active == old) new else active
        save(newMap, nextActive)
    }

    private companion object {
        const val PREFS = "profiles_store"
        const val KEY_PROFILES = "profiles_json"
    }
}
