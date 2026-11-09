package io.github.wilsonnet.droidsentenceminer.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64

/** Source of the current settings. [AiSettingsStore] is the real one; tests provide a fake. */
interface AiSettingsSource {
    suspend fun current(): AiSettings
}

// Two files, so the secrets file can be excluded from backup / device transfer on its own
// (res/xml/backup_rules.xml, data_extraction_rules.xml: `datastore/ai_secrets.preferences_pb`).
private val Context.aiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = AiSettingsStore.SETTINGS_FILE)
private val Context.aiSecretsDataStore: DataStore<Preferences> by preferencesDataStore(name = AiSettingsStore.SECRETS_FILE)

/**
 * AI settings in their own DataStore (`ai_settings`, not DsmPrefs). API keys are AES/GCM
 * encrypted with a Keystore key ([SecretCipher]) and live in a second file (`ai_secrets`) that
 * is excluded from backup. A key that cannot be decrypted (restore onto another device, cleared
 * Keystore) reads as empty, so the user is simply asked to enter it again.
 *
 * Several instances over the same context share the same DataStore files, so the screen and the
 * service can each construct one.
 */
class AiSettingsStore internal constructor(
    private val settings: DataStore<Preferences>,
    private val secrets: DataStore<Preferences>,
    private val cipher: SecretCipher,
) : AiSettingsSource {
    constructor(context: Context) : this(
        context.applicationContext.aiSettingsDataStore,
        context.applicationContext.aiSecretsDataStore,
        KeystoreSecretCipher(),
    )

    /** Settings without key material (what the UI binds to); see [providersWithKey]. */
    val flow: Flow<AiSettings> = settings.data.map { read(it) }

    override suspend fun current(): AiSettings = withKeys(read(settings.data.first()), secrets.data.first())

    /** Settings without any key material (what the UI binds to). */
    suspend fun currentWithoutKeys(): AiSettings = read(settings.data.first())

    /** Which providers have a stored key, without decrypting. */
    suspend fun providersWithKey(): Set<AiProviderType> {
        val prefs = secrets.data.first()
        return AiProviderType.entries.filter { prefs[keyOf(it)] != null }.toSet()
    }

    suspend fun update(transform: (AiSettings) -> AiSettings) {
        settings.edit { prefs ->
            val next = transform(read(prefs))
            write(prefs, next)
        }
    }

    suspend fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }

    /** Stores [apiKey] encrypted; a blank key deletes it. The key is never written anywhere else. */
    suspend fun setApiKey(type: AiProviderType, apiKey: String) {
        val trimmed = apiKey.trim()
        secrets.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(keyOf(type))
            } else {
                val blob = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8))
                prefs[keyOf(type)] = Base64.getEncoder().encodeToString(blob)
            }
        }
    }

    /**
     * Settings saved for another provider (before the app offered only Custom) become the Custom
     * provider's: same model, its endpoint (or the provider's OpenAI-compatible one) and its key,
     * unless Custom already has its own. Idempotent; runs once per process from AppContainer.
     */
    suspend fun migrateToCustom() {
        val saved = current()
        val old = saved.provider
        if (old == AiProviderType.CUSTOM) return
        val from = saved.of(old)
        val custom = saved.of(AiProviderType.CUSTOM)
        if (custom.endpoint.isBlank()) {
            val moved = from.copy(
                apiKey = Secret.EMPTY,
                model = from.model.ifBlank { old.defaultModel },
                endpoint = from.endpoint.ifBlank { openAiCompatibleEndpoint(old) },
            )
            update { it.copy(provider = AiProviderType.CUSTOM, providers = it.providers + (AiProviderType.CUSTOM to moved)) }
        } else {
            update { it.copy(provider = AiProviderType.CUSTOM) }
        }
        if (custom.apiKey.isBlank && !from.apiKey.isBlank) setApiKey(AiProviderType.CUSTOM, from.apiKey.value)
    }

    suspend fun clearAll() {
        settings.edit { it.clear() }
        secrets.edit { it.clear() }
    }

    private fun withKeys(base: AiSettings, secretPrefs: Preferences): AiSettings {
        val providers = AiProviderType.entries.associateWith { type ->
            val stored = secretPrefs[keyOf(type)]
            val key = stored?.let { decode(it) } ?: ""
            base.of(type).copy(apiKey = Secret(key))
        }
        return base.copy(providers = providers)
    }

    private fun decode(stored: String): String? = try {
        cipher.decrypt(Base64.getDecoder().decode(stored))?.toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun read(prefs: Preferences): AiSettings {
        val d = AiSettings()
        val providers = AiProviderType.entries.associateWith { type ->
            fun s(field: String) = prefs[stringPreferencesKey("${type.name}.$field")] ?: ""
            ProviderSettings(
                model = s("model"),
                endpoint = s("endpoint"),
                thinkingLevel = s("thinkingLevel"),
                thinkingMode = s("thinkingMode"),
                thinkingIntensity = s("thinkingIntensity"),
                customThinkingIntensity = s("customThinkingIntensity"),
                routingMode = s("routingMode"),
                providerSlugs = s("providerSlugs"),
                allowFallbacks = prefs[booleanPreferencesKey("${type.name}.allowFallbacks")] ?: true,
                requestBodyJson = s("requestBodyJson"),
            )
        }
        return AiSettings(
            enabled = prefs[ENABLED] ?: d.enabled,
            provider = prefs[PROVIDER]?.let(AiProviderType::fromName) ?: d.provider,
            systemPrompt = prefs[SYSTEM_PROMPT] ?: d.systemPrompt,
            userPrompt = prefs[USER_PROMPT]?.takeIf { it.isNotBlank() } ?: d.userPrompt,
            temperature = (prefs[TEMPERATURE] ?: d.temperature),
            streaming = prefs[STREAMING] ?: d.streaming,
            autoExplain = prefs[AUTO_EXPLAIN] ?: d.autoExplain,
            cancelPendingRequests = prefs[CANCEL_PENDING] ?: d.cancelPendingRequests,
            fallbackForUnknownWords = prefs[FALLBACK] ?: d.fallbackForUnknownWords,
            allowInsecureLocalEndpoint = prefs[INSECURE_LOCAL] ?: d.allowInsecureLocalEndpoint,
            providers = providers,
        )
    }

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, s: AiSettings) {
        prefs[ENABLED] = s.enabled
        prefs[PROVIDER] = s.provider.name
        prefs[SYSTEM_PROMPT] = s.systemPrompt
        prefs[USER_PROMPT] = s.userPrompt
        prefs[TEMPERATURE] = s.temperature
        prefs[STREAMING] = s.streaming
        prefs[AUTO_EXPLAIN] = s.autoExplain
        prefs[CANCEL_PENDING] = s.cancelPendingRequests
        prefs[FALLBACK] = s.fallbackForUnknownWords
        prefs[INSECURE_LOCAL] = s.allowInsecureLocalEndpoint
        for (type in AiProviderType.entries) {
            val p = s.of(type)
            fun put(field: String, value: String) { prefs[stringPreferencesKey("${type.name}.$field")] = value }
            put("model", p.model)
            put("endpoint", p.endpoint)
            put("thinkingLevel", p.thinkingLevel)
            put("thinkingMode", p.thinkingMode)
            put("thinkingIntensity", p.thinkingIntensity)
            put("customThinkingIntensity", p.customThinkingIntensity)
            put("routingMode", p.routingMode)
            put("providerSlugs", p.providerSlugs)
            put("requestBodyJson", p.requestBodyJson)
            prefs[booleanPreferencesKey("${type.name}.allowFallbacks")] = p.allowFallbacks
        }
    }

    private fun keyOf(type: AiProviderType) = stringPreferencesKey("key.${type.name}")

    companion object {
        const val SETTINGS_FILE = "ai_settings"
        const val SECRETS_FILE = "ai_secrets"
        private val ENABLED = booleanPreferencesKey("enabled")
        private val PROVIDER = stringPreferencesKey("provider")
        private val SYSTEM_PROMPT = stringPreferencesKey("systemPrompt")
        private val USER_PROMPT = stringPreferencesKey("userPrompt")
        private val TEMPERATURE = doublePreferencesKey("temperature")
        private val STREAMING = booleanPreferencesKey("streaming")
        private val AUTO_EXPLAIN = booleanPreferencesKey("autoExplain")
        private val CANCEL_PENDING = booleanPreferencesKey("cancelPendingRequests")
        private val FALLBACK = booleanPreferencesKey("fallbackForUnknownWords")
        private val INSECURE_LOCAL = booleanPreferencesKey("allowInsecureLocalEndpoint")
    }
}

/** The chat-completions URL a provider's key works with through the Custom (OpenAI-compatible) provider. */
internal fun openAiCompatibleEndpoint(type: AiProviderType): String = when (type) {
    AiProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    else -> type.defaultEndpoint
}
