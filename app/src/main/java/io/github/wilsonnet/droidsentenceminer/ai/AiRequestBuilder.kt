/*
 * Request building ported from ext/js/comm/ai-provider.js (Yomitan BYOK fork, GPL-3.0-or-later,
 * Copyright (C) 2023-2026 Yomitan Authors). Differences are listed in docs/ai-byok.md.
 */
package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A fully-resolved HTTP request. `toString` never prints headers (they carry the key). */
class BuiltRequest(
    val url: HttpUrl,
    val headers: Map<String, String>,
    val body: JsonObject,
    val provider: AiProviderType,
    val insecureLocal: Boolean,
) {
    override fun toString(): String = "BuiltRequest($provider ${url.scheme}://${url.host}${url.encodedPath})"
}

object AiConfigCheck {
    /** Enabled, key present, model resolvable and (custom) endpoint set. Does not validate the URL itself. */
    fun isConfigured(settings: AiSettings): Boolean {
        if (!settings.enabled) return false
        val p = settings.current
        if (p.apiKey.isBlank) return false
        if (AiRequestBuilder.model(settings).isBlank()) return false
        if (AiRequestBuilder.endpoint(settings).isBlank()) return false
        return true
    }
}

object AiRequestBuilder {
    const val REFERER = "https://github.com/WilsonNet/droid-sentence-miner"
    const val APP_TITLE = "Droid Sentence Miner"
    private val DANGEROUS_KEYS = setOf("__proto__", "prototype", "constructor")
    private val json = Json { isLenient = false }

    fun model(settings: AiSettings): String =
        settings.current.model.trim().ifBlank { settings.provider.defaultModel }

    fun endpoint(settings: AiSettings): String =
        settings.current.endpoint.trim().ifBlank { settings.provider.defaultEndpoint }

    fun temperature(settings: AiSettings): Double {
        val t = settings.temperature
        return if (t.isNaN() || t.isInfinite()) AiSettings.DEFAULT_TEMPERATURE else t.coerceIn(0.0, 2.0)
    }

    /**
     * @param stream whether this is the streaming variant (`stream:true` is forced after the custom-body merge,
     *   as in the reference, so a stored `"stream": false` cannot turn it off).
     * @throws AiException INVALID_SETTINGS for a rejected endpoint or malformed custom JSON.
     */
    fun build(settings: AiSettings, request: AiRequest, stream: Boolean): BuiltRequest {
        val type = settings.provider
        val p = settings.current
        val key = p.apiKey.value.trim()
        val checked = EndpointPolicy.check(endpoint(settings), settings.allowInsecureLocalEndpoint)
        val ok = when (checked) {
            is EndpointPolicy.Result.Ok -> checked
            is EndpointPolicy.Result.Rejected ->
                throw AiException(AiErrorKind.INVALID_SETTINGS, checked.reason, retryable = false)
        }
        val system = PromptTemplate.system(settings, request)
        val user = PromptTemplate.user(settings, request)
        return if (type == AiProviderType.GEMINI) {
            buildGemini(settings, p, key, ok, user, system, stream)
        } else {
            buildOpenAiCompatible(settings, p, key, ok, user, system, stream)
        }
    }

    // ---- OpenAI-compatible: OpenAI, DeepSeek, OpenRouter, custom ----

    private fun buildOpenAiCompatible(
        settings: AiSettings,
        p: ProviderSettings,
        key: String,
        endpoint: EndpointPolicy.Result.Ok,
        user: String,
        system: String,
        stream: Boolean,
    ): BuiltRequest {
        val type = settings.provider
        val model = model(settings)
        val messages = JsonArray(
            buildList {
                if (system.isNotEmpty()) add(message("system", system))
                add(message("user", user))
            },
        )
        var body = buildJsonObject {
            put("model", if (type == AiProviderType.DEEPSEEK || type == AiProviderType.CUSTOM) model.trim() else model)
            put("messages", messages)
            // o-series / gpt-5 reject any temperature but the default, so it is not sent for them.
            if (!(type == AiProviderType.OPENAI && isOpenAiReasoningModel(model))) {
                put("temperature", temperature(settings))
            }
            if (type == AiProviderType.DEEPSEEK) put("stream", false)
        }
        val openRouter = type == AiProviderType.OPENROUTER || isOpenRouterHost(endpoint.url)
        body = when {
            type == AiProviderType.OPENAI -> applyOpenAiReasoning(body, model, p)
            openRouter -> applyOpenRouterProvider(applyOpenRouterReasoning(body, p), p)
            else -> applyThinkingReasoning(body, p) // DeepSeek, custom
        }
        body = applyCustomBody(body, p)
        if ((body["messages"] as? JsonArray)?.isNotEmpty() != true) {
            body = JsonObject(body + ("messages" to messages))
        }
        if (stream) body = JsonObject(body + ("stream" to JsonPrimitive(true)))
        val headers = buildMap {
            put("Authorization", "Bearer $key")
            if (stream) put("Accept", "text/event-stream")
            if (openRouter) {
                put("HTTP-Referer", REFERER)
                put("X-OpenRouter-Title", APP_TITLE)
            }
        }
        return BuiltRequest(endpoint.url, headers, body, type, endpoint.insecureLocal)
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** OpenAI reasoning families (`o1`, `o3-mini`, `o4-mini`, `gpt-5*`), but not the `*-chat` variants. */
    internal fun isOpenAiReasoningModel(model: String): Boolean {
        val m = model.trim().lowercase()
        if (m.contains("chat")) return false
        return Regex("^o\\d+(-.*)?$").matches(m) || m.startsWith("gpt-5")
    }

    private fun applyOpenAiReasoning(body: JsonObject, model: String, p: ProviderSettings): JsonObject {
        if (!isOpenAiReasoningModel(model)) return body
        val m = model.trim().lowercase()
        if (m.startsWith("o1-mini") || m.startsWith("o1-preview")) return body // no reasoning_effort support
        val raw = if (p.thinkingIntensity == "custom") p.customThinkingIntensity.trim() else p.thinkingIntensity.trim()
        val effort = when (raw.lowercase()) {
            "max" -> "high"
            "minimal", "low", "medium", "high" -> raw.lowercase()
            else -> return body
        }
        return JsonObject(body + ("reasoning_effort" to JsonPrimitive(effort)))
    }

    /** DeepSeek / custom endpoints: `thinking: {type}` and `reasoning_effort`, exactly as the reference. */
    private fun applyThinkingReasoning(body: JsonObject, p: ProviderSettings): JsonObject {
        val mode = p.thinkingMode
        val effective = effectiveIntensity(p)
        val hasMode = mode == "enabled" || mode == "disabled"
        val hasIntensity = effective.isNotEmpty()
        if (!hasMode && !hasIntensity) return body
        val out = LinkedHashMap(body)
        if (hasMode) out["thinking"] = buildJsonObject { put("type", mode) }
        if (mode != "disabled" && hasIntensity) out["reasoning_effort"] = JsonPrimitive(effective)
        return JsonObject(out)
    }

    private fun effectiveIntensity(p: ProviderSettings): String =
        if (p.thinkingIntensity == "custom") p.customThinkingIntensity.trim() else p.thinkingIntensity

    private fun applyOpenRouterReasoning(body: JsonObject, p: ProviderSettings): JsonObject {
        val mode = p.thinkingMode
        val intensity = p.thinkingIntensity
        val custom = p.customThinkingIntensity.trim()
        val effective = effectiveIntensity(p)
        val hasMode = mode == "enabled" || mode == "disabled"
        val hasIntensity = effective.isNotEmpty()
        if (!hasMode && !hasIntensity) return body
        val reasoning = LinkedHashMap<String, JsonElement>()
        when {
            mode == "disabled" -> reasoning["effort"] = JsonPrimitive("none")
            intensity == "custom" && custom.isNotEmpty() -> reasoning.putAll(parseCustomReasoning(custom))
            hasIntensity -> reasoning["effort"] = JsonPrimitive(if (effective == "max") "xhigh" else effective)
            hasMode -> reasoning["enabled"] = JsonPrimitive(true)
        }
        reasoning["exclude"] = JsonPrimitive(true)
        return JsonObject(body + ("reasoning" to JsonObject(reasoning)))
    }

    /** `2000` → max_tokens, `{...}` → that object, anything else → effort (`max` → `xhigh`). */
    internal fun parseCustomReasoning(value: String): Map<String, JsonElement> {
        val t = value.trim()
        if (t.isEmpty()) return emptyMap()
        if (t.all { it in '0'..'9' }) {
            t.toIntOrNull()?.let { return mapOf("max_tokens" to JsonPrimitive(it)) }
        }
        if (t.startsWith("{")) {
            try {
                (json.parseToJsonElement(t) as? JsonObject)?.let { return it }
            } catch (_: SerializationException) {
                // invalid JSON falls through to effort
            }
        }
        return mapOf("effort" to JsonPrimitive(if (t == "max") "xhigh" else t))
    }

    internal fun isOpenRouterHost(url: HttpUrl): Boolean =
        url.host == "openrouter.ai" || url.host.endsWith(".openrouter.ai")

    private fun applyOpenRouterProvider(body: JsonObject, p: ProviderSettings): JsonObject {
        val slugs = p.providerSlugs.split(Regex("[\\n,]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val opts = LinkedHashMap<String, JsonElement>()
        if (slugs.isNotEmpty()) {
            val array = JsonArray(slugs.map { JsonPrimitive(it) })
            when (p.routingMode) {
                "order" -> opts["order"] = array
                "only" -> opts["only"] = array
                "ignore" -> opts["ignore"] = array
            }
        }
        opts["allow_fallbacks"] = JsonPrimitive(p.allowFallbacks)
        return JsonObject(body + ("provider" to JsonObject(opts)))
    }

    // ---- Gemini ----

    private fun buildGemini(
        settings: AiSettings,
        p: ProviderSettings,
        key: String,
        endpoint: EndpointPolicy.Result.Ok,
        user: String,
        system: String,
        stream: Boolean,
    ): BuiltRequest {
        val raw = model(settings)
        var modelId = raw.removePrefix("models/")
        var vertex = false
        if (modelId.endsWith("-latest")) modelId = modelId.removeSuffix("-latest")
        if (modelId.endsWith("-vertex")) {
            modelId = modelId.removeSuffix("-vertex")
            vertex = true
        }
        val lowThinking = modelId.contains("low-thinking")
        val apiModel = if (lowThinking) "gemini-3-flash-preview" else modelId
        if (lowThinking) vertex = true
        if (apiModel == "gemini-3-pro-preview" || apiModel == "gemini-3-pro-image-preview") vertex = true
        val gemini3 = apiModel.contains("gemini-3")

        var level = p.thinkingLevel.uppercase()
        if (lowThinking && level.isEmpty()) level = "LOW"
        if (apiModel == "gemini-3-pro-preview" || apiModel == "gemini-3.1-pro-preview") {
            level = when {
                level == "MINIMAL" -> "LOW"
                level == "MEDIUM" -> "HIGH"
                level != "" && level != "LOW" && level != "HIGH" -> ""
                else -> level
            }
        }
        if (!gemini3 || level.isEmpty()) level = ""

        val method = if (stream) "streamGenerateContent" else "generateContent"
        val overridden = settings.current.endpoint.trim().isNotEmpty()
        val base = when {
            overridden -> endpoint.url.toString().trimEnd('/')
            vertex -> VERTEX_BASE
            else -> endpoint.url.toString().trimEnd('/')
        }
        val query = if (stream) "?alt=sse" else ""
        // The key travels in a header, never in the URL (which ends up in logs and proxies).
        val url = "$base/$apiModel:$method$query".toHttpUrlOrNull()
            ?: throw AiException(AiErrorKind.INVALID_SETTINGS, "The Gemini model id is not valid.", retryable = false)

        val generationConfig = buildJsonObject {
            put("temperature", temperature(settings))
            if (level.isNotEmpty()) put("thinkingConfig", buildJsonObject { put("thinkingLevel", level) })
        }
        var body = buildJsonObject {
            put(
                "contents",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            if (vertex) put("role", "user")
                            put("parts", JsonArray(listOf(buildJsonObject { put("text", user) })))
                        },
                    ),
                ),
            )
            put("generationConfig", generationConfig)
            if (system.isNotEmpty()) {
                put("systemInstruction", buildJsonObject { put("parts", JsonArray(listOf(buildJsonObject { put("text", system) }))) })
            }
        }
        body = applyCustomBody(body, p)
        val headers = mapOf("x-goog-api-key" to key) + if (stream) mapOf("Accept" to "text/event-stream") else emptyMap()
        return BuiltRequest(url, headers, body, AiProviderType.GEMINI, endpoint.insecureLocal)
    }

    private const val VERTEX_BASE = "https://aiplatform.googleapis.com/v1/publishers/google/models"

    // ---- custom request-body JSON ----

    /** Validates the text a user typed; null = fine (blank counts as fine). */
    fun validateCustomBody(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            if (json.parseToJsonElement(raw.trim()) is JsonObject) null else "Expected a JSON object."
        } catch (e: SerializationException) {
            "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(120)}"
        } catch (e: IllegalArgumentException) {
            "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(120)}"
        }
    }

    private fun applyCustomBody(body: JsonObject, p: ProviderSettings): JsonObject {
        val raw = p.requestBodyJson.trim()
        if (raw.isEmpty()) return body
        val parsed = try {
            json.parseToJsonElement(raw)
        } catch (e: SerializationException) {
            throw AiException(
                AiErrorKind.INVALID_SETTINGS,
                "Invalid Custom Request Body JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty().take(120)}",
                retryable = false,
            )
        } catch (e: IllegalArgumentException) {
            throw AiException(AiErrorKind.INVALID_SETTINGS, "Invalid Custom Request Body JSON.", retryable = false)
        }
        if (parsed !is JsonObject) {
            throw AiException(AiErrorKind.INVALID_SETTINGS, "Invalid Custom Request Body JSON: expected a JSON object.", retryable = false)
        }
        return merge(body, parsed)
    }

    /** Deep merge, objects recursively, everything else replaced (`mergeRequestBody` in the reference). */
    internal fun merge(target: JsonObject, source: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(target)
        for ((k, v) in source) {
            if (k in DANGEROUS_KEYS) continue
            val existing = target[k]
            out[k] = if (existing is JsonObject && v is JsonObject) merge(existing, v) else v
        }
        return JsonObject(out)
    }
}
