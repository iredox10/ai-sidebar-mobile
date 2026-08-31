package com.iredox.aisidebar.data

import android.content.Context

data class ProviderSettings(
    val provider: String = "openai",
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    val customName: String = "",
    val customBaseUrl: String = "",
    val customModels: String = "",
    val searchEnabled: Boolean = false,
    val autoSearch: Boolean = true,
    val agenticTools: Boolean = true
) {
    fun resolvedEndpoint(): String = when (provider) {
        "openai" -> "https://api.openai.com/v1/chat/completions"
        "deepseek" -> "https://api.deepseek.com/chat/completions"
        "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
        "custom" -> (if (customBaseUrl.isNotBlank()) customBaseUrl.trimEnd('/') + "/chat/completions" else endpoint)
        else -> endpoint
    }
    fun displayProvider(): String = when (provider) {
        "openai" -> "OpenAI"
        "anthropic" -> "Anthropic"
        "google" -> "Google"
        "deepseek" -> "DeepSeek"
        "openrouter" -> "OpenRouter"
        "custom" -> "Custom"
        else -> provider
    }
}

/** Persists non-secret provider choices. Credentials remain in [SecureKeyStore]. */
class ProviderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): ProviderSettings {
        val rawProvider = preferences.getString(PROVIDER, null) ?: ProviderSettings().provider
        // migrate legacy "OpenAI-compatible" to "openai"
        val provider = when (rawProvider) {
            "OpenAI-compatible" -> "openai"
            "Anthropic" -> "anthropic"
            "Google" -> "google"
            else -> rawProvider.lowercase().takeIf { it in setOf("openai","anthropic","google","deepseek","openrouter","custom") } ?: rawProvider.lowercase()
        }
        return ProviderSettings(
            provider = provider,
            endpoint = preferences.getString(ENDPOINT, null) ?: ProviderSettings().endpoint,
            model = preferences.getString(MODEL, null) ?: ProviderSettings().model,
            systemPrompt = preferences.getString(SYSTEM_PROMPT, null) ?: ProviderSettings().systemPrompt,
            temperature = preferences.getFloat(TEMPERATURE, ProviderSettings().temperature),
            customName = preferences.getString(CUSTOM_NAME, null) ?: "",
            customBaseUrl = preferences.getString(CUSTOM_BASE_URL, null) ?: "",
            customModels = preferences.getString(CUSTOM_MODELS, null) ?: "",
            searchEnabled = preferences.getBoolean(SEARCH_ENABLED, false),
            autoSearch = preferences.getBoolean(AUTO_SEARCH, true),
            agenticTools = preferences.getBoolean(AGENTIC_TOOLS, true)
        )
    }

    fun write(settings: ProviderSettings) {
        preferences.edit()
            .putString(PROVIDER, settings.provider)
            .putString(ENDPOINT, settings.endpoint)
            .putString(MODEL, settings.model)
            .putString(SYSTEM_PROMPT, settings.systemPrompt)
            .putFloat(TEMPERATURE, settings.temperature)
            .putString(CUSTOM_NAME, settings.customName)
            .putString(CUSTOM_BASE_URL, settings.customBaseUrl)
            .putString(CUSTOM_MODELS, settings.customModels)
            .putBoolean(SEARCH_ENABLED, settings.searchEnabled)
            .putBoolean(AUTO_SEARCH, settings.autoSearch)
            .putBoolean(AGENTIC_TOOLS, settings.agenticTools)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "provider_settings"
        const val PROVIDER = "provider"
        const val ENDPOINT = "endpoint"
        const val MODEL = "model"
        const val SYSTEM_PROMPT = "systemPrompt"
        const val TEMPERATURE = "temperature"
        const val CUSTOM_NAME = "customName"
        const val CUSTOM_BASE_URL = "customBaseUrl"
        const val CUSTOM_MODELS = "customModels"
        const val SEARCH_ENABLED = "searchEnabled"
        const val AUTO_SEARCH = "autoSearch"
        const val AGENTIC_TOOLS = "agenticTools"
    }
}
