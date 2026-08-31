package com.iredox.aisidebar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores provider credentials encrypted with a device-bound Android Keystore key. */
class SecureKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        const val KEY_OPENAI = "openaiKey"
        const val KEY_ANTHROPIC = "anthropicKey"
        const val KEY_GOOGLE = "googleKey"
        const val KEY_DEEPSEEK = "deepseekKey"
        const val KEY_OPENROUTER = "openrouterKey"
        const val KEY_TAVILY = "tavilyKey"
        const val KEY_CUSTOM = "customKey"
        val ALL_KEYS = listOf(KEY_OPENAI, KEY_ANTHROPIC, KEY_GOOGLE, KEY_DEEPSEEK, KEY_OPENROUTER, KEY_TAVILY, KEY_CUSTOM)
    }

    fun readApiKey(): String? = readKey(KEY_OPENAI) ?: runCatching {
        val stored = preferences.getString(API_KEY_NAME, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Invalid encrypted API key" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        }
        String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    fun writeApiKey(value: String) {
        // Backward-compatible single-key API now aliases openaiKey; legacy slot migrated lazily.
        writeKey(KEY_OPENAI, value)
        if (value.isBlank()) {
            preferences.edit().remove(API_KEY_NAME).apply()
        } else {
            // mirror to legacy for downgrade safety until all clients migrate
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            val payload = cipher.iv + encrypted
            preferences.edit().putString(API_KEY_NAME, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        }
    }

    fun readKey(providerKey: String): String? = runCatching {
        val storageKey = keyPrefName(providerKey)
        val stored = preferences.getString(storageKey, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Invalid encrypted key" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        }
        String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    fun writeKey(providerKey: String, value: String) {
        val storageKey = keyPrefName(providerKey)
        if (value.isBlank()) {
            preferences.edit().remove(storageKey).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit().putString(storageKey, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun readAllKeys(): Map<String, String?> = ALL_KEYS.associateWith { readKey(it) }

    fun hasAnyKey(): Boolean = ALL_KEYS.any { readKey(it) != null } || readApiKey() != null

    private fun keyPrefName(providerKey: String) = "key_$providerKey"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_provider_settings"
        const val API_KEY_NAME = "api_key"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ai_sidebar_provider_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
