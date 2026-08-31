package com.iredox.aisidebar.data

import android.content.Context

data class ProviderSettings(
    val provider: String = "OpenAI-compatible",
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "gpt-4o-mini"
)

/** Persists non-secret provider choices. Credentials remain in [SecureKeyStore]. */
class ProviderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): ProviderSettings = ProviderSettings(
        provider = preferences.getString(PROVIDER, null) ?: ProviderSettings().provider,
        endpoint = preferences.getString(ENDPOINT, null) ?: ProviderSettings().endpoint,
        model = preferences.getString(MODEL, null) ?: ProviderSettings().model
    )

    fun write(settings: ProviderSettings) {
        preferences.edit()
            .putString(PROVIDER, settings.provider)
            .putString(ENDPOINT, settings.endpoint)
            .putString(MODEL, settings.model)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "provider_settings"
        const val PROVIDER = "provider"
        const val ENDPOINT = "endpoint"
        const val MODEL = "model"
    }
}
