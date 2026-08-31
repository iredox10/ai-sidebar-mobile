package com.iredox.aisidebar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PromptPreset(val name: String, val prompt: String)

class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<PromptPreset> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val prompt = o.optString("prompt").trim()
                if (name.isNotEmpty() && prompt.isNotEmpty()) add(PromptPreset(name, prompt))
            }
        }
    }.getOrDefault(emptyList())

    fun save(presets: List<PromptPreset>) {
        val arr = JSONArray().apply {
            presets.forEach { put(JSONObject().put("name", it.name).put("prompt", it.prompt)) }
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(preset: PromptPreset) {
        val list = load().toMutableList()
        list.add(preset)
        save(list)
    }

    fun remove(index: Int) {
        val list = load().toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            save(list)
        }
    }

    companion object {
        const val PREFS = "preset_store"
        const val KEY = "presets_json"
    }
}

fun applyPresetPrompt(preset: PromptPreset, currentPrompt: String, selection: String? = null): String {
    var p = preset.prompt
    val sel = selection ?: currentPrompt.trim()
    if (p.contains("{selection}")) p = p.replace("{selection}", sel.ifEmpty { currentPrompt })
    if (p.contains("{page}")) {
        // {page} placeholder treated as current prompt extra — caller should inject screen context if desired
        p = p.replace("{page}", sel.ifEmpty { "" })
    }
    return p
}
