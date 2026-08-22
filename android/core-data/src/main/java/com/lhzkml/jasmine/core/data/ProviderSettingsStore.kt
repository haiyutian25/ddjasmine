package com.lhzkml.jasmine.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lhzkml.jasmine.core.agent.ProviderProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The provider connection settings the chat page and settings UI share. */
data class ProviderSettings(
    val apiAddress: String = "",
    val apiKey: String = "",
    val model: String = "",
    val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS,
    val contextLength: Int = 0,
    /** `null` means omit the output cap and use the provider maximum. */
    val maxOutputTokens: Int? = null,
) {
    val isConfigured: Boolean
        get() = apiAddress.isNotBlank() && model.isNotBlank() && contextLength > 0 &&
            (maxOutputTokens == null || maxOutputTokens in 1 until contextLength)
}

/**
 * Persists provider settings. The API key is encrypted at rest with an
 * Android Keystore AES-GCM key (never plaintext in SharedPreferences);
 * baseUrl and model are not secrets.
 */
@Singleton
class ProviderSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureKey = SecureKeyStore(context)

    suspend fun load(): ProviderSettings = withContext(Dispatchers.IO) {
        ProviderSettings(
            apiAddress = prefs.getString(KEY_API_ADDRESS, null).orEmpty(),
            apiKey = secureKey.decrypt(prefs.getString(KEY_API_KEY, null).orEmpty()),
            model = prefs.getString(KEY_MODEL, null).orEmpty(),
            protocol = prefs.getString(KEY_PROTOCOL, null)
                ?.let { runCatching { ProviderProtocol.valueOf(it) }.getOrNull() }
                ?: ProviderProtocol.CHAT_COMPLETIONS,
            contextLength = prefs.getInt(KEY_CONTEXT_LENGTH, 0),
            maxOutputTokens = prefs.getInt(KEY_MAX_OUTPUT_TOKENS, 0).takeIf { it > 0 },
        )
    }

    suspend fun save(settings: ProviderSettings) = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
            .putString(KEY_API_ADDRESS, settings.apiAddress)
            .putString(KEY_API_KEY, secureKey.encrypt(settings.apiKey))
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_PROTOCOL, settings.protocol.name)
            .putInt(KEY_CONTEXT_LENGTH, settings.contextLength)
        settings.maxOutputTokens?.let { editor.putInt(KEY_MAX_OUTPUT_TOKENS, it) }
            ?: editor.remove(KEY_MAX_OUTPUT_TOKENS)
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "provider_settings"
        const val KEY_API_ADDRESS = "api_address"
        const val KEY_API_KEY = "api_key_enc"
        const val KEY_MODEL = "model"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_CONTEXT_LENGTH = "context_length"
        const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
    }
}

/**
 * AES-GCM encryption of single secrets under an Android Keystore key the
 * app owns. Keystore keys never leave secure hardware-backed storage; only
 * the ciphertext lands in preferences.
 */
private class SecureKeyStore(context: Context) {

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    init {
        if (!keyStore.containsAlias(ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        }
    }

    private val key: SecretKey
        get() = keyStore.getKey(ALIAS, null) as SecretKey

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val parts = stored.split(":")
        if (parts.size != 2) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Undecryptable legacy/corrupt blobs read as absent, never crash.
            ""
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val ALIAS = "jasmine-provider-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
