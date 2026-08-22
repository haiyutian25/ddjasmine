package com.lhzkml.jasmine.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lhzkml.jasmine.core.agent.ProviderProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One user-defined provider connection. A provider owns several selected
 * models; the chat picks any of them directly — every provider is active at
 * once, there is no on/off or single active provider.
 */
data class ProviderEntry(
    val id: String,
    val name: String,
    val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS,
    val apiAddress: String = "",
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    val contextLength: Int = 0,
    /** `null` means omit the output cap and use the provider maximum. */
    val maxOutputTokens: Int? = null,
)

/**
 * Persists the provider list (many providers, all active at once). Each API
 * key is encrypted at rest with an Android Keystore AES-GCM key; the rest of
 * an entry is not secret.
 */
@Singleton
class ProviderSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureKey = SecureKeyStore(context)

    suspend fun providers(): List<ProviderEntry> = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY_PROVIDERS, null)
        val list = stored?.let { parse(it) }.orEmpty()
        if (list.isEmpty()) migrateLegacySingleProvider() else list
    }

    suspend fun save(entries: List<ProviderEntry>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_PROVIDERS, encode(entries, secureKey)).apply()
    }

    /** The model currently used by the chat, when pinned. */
    suspend fun activeModelId(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ACTIVE_MODEL, null)
    }

    suspend fun setActiveModel(modelId: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_ACTIVE_MODEL, modelId).apply()
    }

    suspend fun newEntry(): ProviderEntry = ProviderEntry(
        id = UUID.randomUUID().toString(),
        name = "供应商 ${providers().size + 1}",
    )

    private fun parse(stored: String): List<ProviderEntry> = runCatching {
        val array = JSONArray(stored)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            ProviderEntry(
                id = obj.getString("id"),
                name = obj.getString("name"),
                protocol = runCatching { ProviderProtocol.valueOf(obj.getString("protocol")) }
                    .getOrDefault(ProviderProtocol.CHAT_COMPLETIONS),
                apiAddress = obj.getString("apiAddress"),
                apiKey = secureKey.decrypt(obj.getString("apiKey")),
                models = obj.optJSONArray("models")?.let { models ->
                    (0 until models.length()).map { models.getString(it) }
                }.orEmpty(),
                contextLength = obj.getInt("contextLength"),
                maxOutputTokens = if (obj.isNull("maxOutputTokens")) null
                else obj.optInt("maxOutputTokens").takeIf { it > 0 },
            )
        }
    }.getOrDefault(emptyList())

    /**
     * One-time migration from the legacy single-provider keys: the old
     * address/key/model become one provider entry named "默认".
     */
    private fun migrateLegacySingleProvider(): List<ProviderEntry> {
        val legacy = LegacySingleProvider(prefs, secureKey)
        if (legacy.apiAddress.isBlank()) return emptyList()
        val entry = ProviderEntry(
            id = UUID.randomUUID().toString(),
            name = "默认",
            protocol = legacy.protocol,
            apiAddress = legacy.apiAddress,
            apiKey = legacy.apiKey,
            models = listOf(legacy.model).filter(String::isNotBlank),
            contextLength = legacy.contextLength,
            maxOutputTokens = legacy.maxOutputTokens,
        )
        prefs.edit().putString(KEY_PROVIDERS, encode(listOf(entry), secureKey)).apply()
        return listOf(entry)
    }

    private class LegacySingleProvider(
        private val prefs: android.content.SharedPreferences,
        private val secureKey: SecureKeyStore,
    ) {
        val apiAddress: String get() = prefs.getString("api_address", null).orEmpty()
        val apiKey: String get() = secureKey.decrypt(prefs.getString("api_key_enc", null).orEmpty())
        val model: String get() = prefs.getString("model", null).orEmpty()
        val protocol: ProviderProtocol
            get() = prefs.getString("protocol", null)
                ?.let { runCatching { ProviderProtocol.valueOf(it) }.getOrNull() }
                ?: ProviderProtocol.CHAT_COMPLETIONS
        val contextLength: Int get() = prefs.getInt("context_length", 0)
        val maxOutputTokens: Int? get() = prefs.getInt("max_output_tokens", 0).takeIf { it > 0 }
    }

    private companion object {
        const val PREFS_NAME = "provider_settings"
        const val KEY_PROVIDERS = "providers"
        const val KEY_ACTIVE_MODEL = "active_model_id"

        fun encode(entries: List<ProviderEntry>, secureKey: SecureKeyStore): String {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("name", entry.name)
                        .put("protocol", entry.protocol.name)
                        .put("apiAddress", entry.apiAddress)
                        .put("apiKey", secureKey.encrypt(entry.apiKey))
                        .put("models", JSONArray(entry.models))
                        .put("contextLength", entry.contextLength)
                        .put("maxOutputTokens", entry.maxOutputTokens ?: JSONObject.NULL)
                )
            }
            return array.toString()
        }
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
