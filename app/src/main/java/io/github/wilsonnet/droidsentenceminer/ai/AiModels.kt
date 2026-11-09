/*
 * Droid Sentence Miner — BYOK AI explanations.
 *
 * Ported from the Yomitan BYOK fork (arrow-poppa/yomitan-byok-ai, branch
 * feature/byok-ai-explanations): ext/js/comm/ai-provider.js,
 * ext/js/display/ai-explanation-generator.js and the `ai` block of options-schema.json.
 * Copyright (C) 2023-2026 Yomitan Authors, GPL-3.0-or-later, as is this project.
 */
package io.github.wilsonnet.droidsentenceminer.ai

/** Providers a user can bring their own key for. [CUSTOM] is any OpenAI-compatible endpoint. */
enum class AiProviderType(
    val label: String,
    val defaultModel: String,
    /** Full chat-completions URL (Gemini: the `models` base URL); blank = the user must set one. */
    val defaultEndpoint: String,
) {
    OPENAI("OpenAI", "gpt-4o-mini", "https://api.openai.com/v1/chat/completions"),
    GEMINI("Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta/models"),
    DEEPSEEK("DeepSeek", "deepseek-chat", "https://api.deepseek.com/chat/completions"),
    OPENROUTER("OpenRouter", "openai/gpt-4o-mini", "https://openrouter.ai/api/v1/chat/completions"),
    CUSTOM("Custom (OpenAI-compatible)", "", ""),
    ;

    companion object {
        fun fromName(name: String?): AiProviderType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OPENAI
    }
}

/**
 * What to explain. [target] is the ORIGINAL selected surface ("running"), never replaced by the
 * lemma; [headword]/[reading] are an optional dictionary hint sent as a separate explicit note.
 */
data class AiRequest(
    val target: String,
    val sentence: String,
    val language: String?,
    val headword: String? = null,
    val reading: String? = null,
)

/** Why an [AiExplanationState.Error] happened; the UI offers "configure" for some kinds. */
enum class AiErrorKind { INVALID_KEY, INVALID_SETTINGS, RATE_LIMITED, SERVER, TIMEOUT, NETWORK, EMPTY, REJECTED, OTHER }

sealed interface AiExplanationState {
    /** Nothing requested yet (the popup shows the "Explain" button). */
    data object Idle : AiExplanationState

    /** AI is disabled or incomplete (no key/model/endpoint). Nothing was sent. */
    data object NotConfigured : AiExplanationState

    data object Loading : AiExplanationState

    /** Partial answer so far (cumulative, not a delta). */
    data class Streaming(val text: String) : AiExplanationState

    data class Done(val text: String, val fromCache: Boolean) : AiExplanationState

    data class Error(
        val message: String,
        val retryable: Boolean,
        val kind: AiErrorKind = AiErrorKind.OTHER,
    ) : AiExplanationState
}

/** A secret whose `toString` never prints the value, so a log line or exception cannot leak it. */
class Secret(val value: String) {
    val isBlank: Boolean get() = value.isBlank()
    override fun toString(): String = if (value.isBlank()) "Secret(empty)" else "Secret(***)"
    override fun equals(other: Any?): Boolean = other is Secret && other.value == value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        val EMPTY = Secret("")
    }
}

/** Per-provider settings (everything except the key lives in the `ai_settings` DataStore). */
data class ProviderSettings(
    val apiKey: Secret = Secret.EMPTY,
    /** Blank = the provider's default model. */
    val model: String = "",
    /** Blank = the provider's default endpoint. Required for [AiProviderType.CUSTOM]. */
    val endpoint: String = "",
    /** Gemini 3.x only: "", MINIMAL, LOW, MEDIUM, HIGH. */
    val thinkingLevel: String = "",
    /** "", "enabled", "disabled" (DeepSeek / OpenRouter / custom). */
    val thinkingMode: String = "",
    /** "", "low", "medium", "high", "max", "custom". OpenAI reasoning models: minimal/low/medium/high. */
    val thinkingIntensity: String = "",
    val customThinkingIntensity: String = "",
    /** OpenRouter only: "", "order", "only", "ignore". */
    val routingMode: String = "",
    /** OpenRouter only: provider slugs, comma/newline separated. */
    val providerSlugs: String = "",
    val allowFallbacks: Boolean = true,
    /** JSON object deep-merged over the request body. */
    val requestBodyJson: String = "",
)

/**
 * The app only offers [AiProviderType.CUSTOM] (any OpenAI-compatible endpoint: OpenAI, OpenRouter,
 * DeepSeek, Gemini's OpenAI endpoint, a local server), like Yomitan BYOK's "Custom" provider with
 * all of its options. The other types stay for the request builder and old saved settings, which
 * [AiSettingsStore.migrateToCustom] moves over.
 */
data class AiSettings(
    val enabled: Boolean = false,
    val provider: AiProviderType = AiProviderType.CUSTOM,
    val systemPrompt: String = "",
    val userPrompt: String = DEFAULT_USER_PROMPT,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val streaming: Boolean = true,
    /**
     * Explain as soon as a word is looked up (Yomitan's `autoGenerateOnLookup`, on by default there
     * too). Nothing is sent while [enabled] is off, and that is off by default.
     */
    val autoExplain: Boolean = true,
    /**
     * Drop an unfinished request when its popup closes or another word is looked up. Off: it
     * finishes in the background and its answer is cached (a cancelled call is usually billed anyway).
     */
    val cancelPendingRequests: Boolean = true,
    /** Offer an AI explanation when no local dictionary entry exists ([AiFallback]). */
    val fallbackForUnknownWords: Boolean = false,
    /** Acknowledges that an `http://` loopback/LAN endpoint sends the key unencrypted. */
    val allowInsecureLocalEndpoint: Boolean = false,
    val providers: Map<AiProviderType, ProviderSettings> = emptyMap(),
) {
    fun of(type: AiProviderType): ProviderSettings = providers[type] ?: ProviderSettings()
    val current: ProviderSettings get() = of(provider)

    companion object {
        const val DEFAULT_TEMPERATURE = 0.7
        const val DEFAULT_USER_PROMPT =
            "Explain the meaning of '{{target}}' in the following sentence: '{{sentence}}'. " +
                "Provide a concise explanation focusing on the word's usage and meaning in this specific context."
    }
}
