package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestBuilderTest {
    private fun build(settings: AiSettings, request: AiRequest = REQ, stream: Boolean = false) =
        AiRequestBuilder.build(settings, request, stream)

    private fun openai(model: String = "gpt-4o-mini", p: ProviderSettings = ProviderSettings(), mutate: (AiSettings) -> AiSettings = { it }) =
        testSettings(AiProviderType.OPENAI, "https://api.openai.com/v1/chat/completions", provider = p.copy(model = model), mutate = mutate)

    private fun messages(b: BuiltRequest) = b.body["messages"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `OpenAI body has model, user message and temperature, and no stream flag`() {
        val b = build(openai())
        assertEquals("https://api.openai.com/v1/chat/completions", b.url.toString())
        assertEquals("gpt-4o-mini", b.body["model"]!!.jsonPrimitive.content)
        assertEquals(0.7, b.body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertFalse("stream" in b.body)
        assertEquals(1, messages(b).size)
        assertEquals("user", messages(b)[0]["role"]!!.jsonPrimitive.content)
        assertEquals("Bearer $TEST_KEY", b.headers["Authorization"])
    }

    @Test
    fun `system prompt becomes a system message and streaming forces stream true`() {
        val b = build(openai(mutate = { it.copy(systemPrompt = "You teach {{language}}.") }), stream = true)
        assertEquals(listOf("system", "user"), messages(b).map { it["role"]!!.jsonPrimitive.content })
        assertEquals("You teach en.", messages(b)[0]["content"]!!.jsonPrimitive.content)
        assertTrue(b.body["stream"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `temperature is clamped to 0 through 2`() {
        assertEquals(2.0, build(openai(mutate = { it.copy(temperature = 9.0) })).body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, build(openai(mutate = { it.copy(temperature = -1.0) })).body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.7, build(openai(mutate = { it.copy(temperature = Double.NaN) })).body["temperature"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `prompt keeps the original surface as target even with a headword hint`() {
        val req = AiRequest("running", "He was running late.", "en", headword = "run", reading = "rʌn")
        val user = messages(build(openai(), req))[0]["content"]!!.jsonPrimitive.content
        assertTrue(user, user.startsWith("Explain the meaning of 'running' in the following sentence: 'He was running late.'."))
        assertTrue(user, user.contains("the dictionary headword is 'run'"))
        assertTrue(user, user.contains("its reading is 'rʌn'"))
        assertTrue(user, user.contains("the selected text is exactly 'running'"))
        assertFalse("lemma must not replace the target", user.contains("meaning of 'run'"))
    }

    @Test
    fun `no hint note when the headword equals the surface and there is no reading`() {
        val req = AiRequest("run", "I run.", "en", headword = "run")
        assertFalse(messages(build(openai(), req))[0]["content"]!!.jsonPrimitive.content.contains("Dictionary note"))
    }

    @Test
    fun `placeholders are filled in one pass and inserted text is not re-expanded`() {
        val req = AiRequest("{{sentence}}", "x {{target}} y", "ja")
        val s = openai(mutate = { it.copy(userPrompt = "T={{target}} S={{sentence}} L={{language}} \$1 \\ {{unknown}}") })
        assertEquals(
            "T={{sentence}} S=x {{target}} y L=ja \$1 \\ {{unknown}}",
            messages(build(s, req))[0]["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `OpenAI reasoning models drop temperature and send reasoning_effort`() {
        val b = build(openai("o3-mini", ProviderSettings(thinkingIntensity = "high")))
        assertFalse("temperature" in b.body)
        assertEquals("high", b.body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("high", build(openai("gpt-5-mini", ProviderSettings(thinkingIntensity = "max"))).body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("low", build(openai("gpt-5", ProviderSettings(thinkingIntensity = "custom", customThinkingIntensity = "low"))).body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse("reasoning_effort" in build(openai("gpt-5", ProviderSettings(thinkingIntensity = "bogus"))).body)
    }

    @Test
    fun `OpenAI non-reasoning models never get reasoning params`() {
        for (model in listOf("gpt-4o-mini", "gpt-4o", "gpt-5-chat-latest", "gpt-3.5-turbo", "o")) {
            val b = build(openai(model, ProviderSettings(thinkingIntensity = "high", thinkingMode = "enabled")))
            assertFalse(model, "reasoning_effort" in b.body || "thinking" in b.body || "reasoning" in b.body)
            assertTrue(model, "temperature" in b.body)
        }
        assertFalse("reasoning_effort" in build(openai("o1-mini", ProviderSettings(thinkingIntensity = "high"))).body)
    }

    @Test
    fun `DeepSeek sends thinking type and reasoning_effort, trims the model and defaults stream false`() {
        val s = testSettings(
            AiProviderType.DEEPSEEK, "https://api.deepseek.com/chat/completions",
            provider = ProviderSettings(model = " deepseek-reasoner ", thinkingMode = "enabled", thinkingIntensity = "max"),
        )
        val b = build(s)
        assertEquals("deepseek-reasoner", b.body["model"]!!.jsonPrimitive.content)
        assertEquals("enabled", b.body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("max", b.body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(b.body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(build(s, stream = true).body["stream"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `disabled thinking sends no effort`() {
        val s = testSettings(
            AiProviderType.DEEPSEEK, "https://api.deepseek.com/chat/completions",
            provider = ProviderSettings(thinkingMode = "disabled", thinkingIntensity = "high"),
        )
        val b = build(s)
        assertEquals("disabled", b.body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("reasoning_effort" in b.body)
    }

    private fun openRouter(p: ProviderSettings) =
        testSettings(AiProviderType.OPENROUTER, "https://openrouter.ai/api/v1/chat/completions", provider = p)

    @Test
    fun `OpenRouter routing maps order only and ignore with allow_fallbacks`() {
        for ((mode, field) in listOf("order" to "order", "only" to "only", "ignore" to "ignore")) {
            val b = build(openRouter(ProviderSettings(routingMode = mode, providerSlugs = " alpha,\n beta ,, ", allowFallbacks = false)))
            val provider = b.body["provider"]!!.jsonObject
            assertEquals(mode, listOf("alpha", "beta"), provider[field]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse(provider["allow_fallbacks"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `OpenRouter without slugs still carries allow_fallbacks and its attribution headers`() {
        val b = build(openRouter(ProviderSettings()))
        assertEquals(setOf("allow_fallbacks"), b.body["provider"]!!.jsonObject.keys)
        assertEquals(AiRequestBuilder.REFERER, b.headers["HTTP-Referer"])
        assertEquals(AiRequestBuilder.APP_TITLE, b.headers["X-OpenRouter-Title"])
    }

    @Test
    fun `OpenRouter normalizes reasoning`() {
        fun reasoning(p: ProviderSettings) = build(openRouter(p)).body["reasoning"]!!.jsonObject
        assertEquals(mapOf("effort" to "none", "exclude" to true), reasoning(ProviderSettings(thinkingMode = "disabled")).plain())
        assertEquals(mapOf("enabled" to true, "exclude" to true), reasoning(ProviderSettings(thinkingMode = "enabled")).plain())
        assertEquals(mapOf("effort" to "high", "exclude" to true), reasoning(ProviderSettings(thinkingIntensity = "high")).plain())
        assertEquals(mapOf("effort" to "xhigh", "exclude" to true), reasoning(ProviderSettings(thinkingIntensity = "max")).plain())
        assertEquals(
            mapOf("effort" to "medium", "exclude" to true),
            reasoning(ProviderSettings(thinkingIntensity = "custom", customThinkingIntensity = "medium")).plain(),
        )
        assertEquals(
            mapOf("max_tokens" to 2000, "exclude" to true),
            reasoning(ProviderSettings(thinkingIntensity = "custom", customThinkingIntensity = "2000")).plain(),
        )
        assertEquals(
            mapOf("max_tokens" to 3000, "summary" to "auto", "exclude" to true),
            reasoning(ProviderSettings(thinkingIntensity = "custom", customThinkingIntensity = """{"max_tokens":3000,"summary":"auto"}""")).plain(),
        )
        assertFalse("reasoning" in build(openRouter(ProviderSettings())).body)
        assertFalse("thinking" in build(openRouter(ProviderSettings(thinkingMode = "enabled"))).body)
    }

    private fun JsonObject.plain(): Map<String, Any> = mapValues { (_, v) ->
        val p = v.jsonPrimitive
        when {
            p.isString -> p.content
            p.content == "true" || p.content == "false" -> p.boolean
            else -> p.int
        }
    }

    @Test
    fun `a custom endpoint on openrouter dot ai is treated as OpenRouter`() {
        val s = testSettings(
            AiProviderType.CUSTOM, "https://openrouter.ai/api/v1/chat/completions",
            provider = ProviderSettings(routingMode = "only", providerSlugs = "x"),
        )
        val b = build(s)
        assertNotNull(b.body["provider"])
        assertNotNull(b.headers["HTTP-Referer"])
        // and a plain custom endpoint is not
        val plain = build(testSettings(AiProviderType.CUSTOM, "https://llm.example.com/v1/chat/completions"))
        assertNull(plain.body["provider"])
        assertNull(plain.headers["HTTP-Referer"])
    }

    @Test
    fun `custom JSON deep-merges, cannot remove messages and cannot turn streaming off`() {
        val s = testSettings(
            AiProviderType.OPENROUTER, "https://openrouter.ai/api/v1/chat/completions",
            provider = ProviderSettings(
                routingMode = "order", providerSlugs = "alpha",
                requestBodyJson = """{"provider":{"sort":"latency"},"temperature":1.25,"messages":null,"stream":false,"__proto__":{"x":1}}""",
            ),
        )
        val b = build(s, stream = true)
        val provider = b.body["provider"]!!.jsonObject
        assertEquals(listOf("alpha"), provider["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("latency", provider["sort"]!!.jsonPrimitive.content)
        assertTrue(provider["allow_fallbacks"]!!.jsonPrimitive.boolean)
        assertEquals(1.25, b.body["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(1, b.body["messages"]!!.jsonArray.size)
        assertTrue(b.body["stream"]!!.jsonPrimitive.boolean)
        assertFalse("__proto__" in b.body)
    }

    @Test
    fun `invalid custom JSON and non-objects are rejected with INVALID_SETTINGS`() {
        for (raw in listOf("{invalid", "[]", "42", "\"s\"")) {
            val e = assertThrows(AiException::class.java) {
                build(testSettings(provider = ProviderSettings(requestBodyJson = raw)))
            }
            assertEquals(raw, AiErrorKind.INVALID_SETTINGS, e.kind)
            assertTrue(e.message!!.startsWith("Invalid Custom Request Body JSON"))
        }
        assertNull(AiRequestBuilder.validateCustomBody(""))
        assertNull(AiRequestBuilder.validateCustomBody("""{"a":1}"""))
        assertNotNull(AiRequestBuilder.validateCustomBody("[1]"))
        assertNotNull(AiRequestBuilder.validateCustomBody("{oops"))
    }

    @Test
    fun `custom body works for Gemini too`() {
        val s = testSettings(
            AiProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta/models",
            provider = ProviderSettings(requestBodyJson = """{"generationConfig":{"maxOutputTokens":300},"safetySettings":[]}"""),
        )
        val b = build(s)
        val gc = b.body["generationConfig"]!!.jsonObject
        assertEquals(300, gc["maxOutputTokens"]!!.jsonPrimitive.int)
        assertNotNull(gc["temperature"])
        assertEquals(JsonArray(emptyList()), b.body["safetySettings"])
    }

    // ---- Gemini ----

    private fun gemini(model: String = "gemini-2.5-flash", level: String = "", endpoint: String = "") =
        testSettings(
            AiProviderType.GEMINI,
            endpoint = endpoint.ifEmpty { AiProviderType.GEMINI.defaultEndpoint },
            provider = ProviderSettings(model = model, thinkingLevel = level),
            mutate = { it.copy(systemPrompt = "sys") },
        ).let { s -> if (endpoint.isEmpty()) s.withProvider { it.copy(endpoint = "") } else s }

    @Test
    fun `Gemini puts the key in a header, never in the URL`() {
        val b = build(gemini(), stream = true)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            b.url.toString(),
        )
        assertEquals(TEST_KEY, b.headers["x-goog-api-key"])
        assertFalse(b.url.toString().contains(TEST_KEY))
        assertFalse(b.url.toString().contains("key="))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            build(gemini()).url.toString(),
        )
    }

    @Test
    fun `Gemini body carries systemInstruction and generationConfig temperature`() {
        val b = build(gemini())
        assertEquals("sys", b.body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val content = b.body["contents"]!!.jsonArray[0].jsonObject
        assertFalse("role" in content)
        assertTrue(content["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("'running'"))
        assertEquals(0.7, b.body["generationConfig"]!!.jsonObject["temperature"]!!.jsonPrimitive.double, 0.0)
        assertFalse("thinkingConfig" in b.body["generationConfig"]!!.jsonObject)
    }

    @Test
    fun `Gemini thinkingConfig only for Gemini 3 models, with the reference's pro mapping`() {
        fun level(model: String, l: String) = build(gemini(model, l)).body["generationConfig"]!!.jsonObject["thinkingConfig"]
            ?.jsonObject?.get("thinkingLevel")?.jsonPrimitive?.content
        assertNull(level("gemini-2.5-flash", "HIGH"))
        assertEquals("HIGH", level("gemini-3-flash-preview", "high"))
        assertNull(level("gemini-3-flash-preview", ""))
        assertEquals("LOW", level("gemini-3.1-pro-preview", "MINIMAL"))
        assertEquals("HIGH", level("gemini-3.1-pro-preview", "MEDIUM"))
        assertEquals("MEDIUM", level("gemini-3-flash-preview", "MEDIUM"))
    }

    @Test
    fun `Gemini model id normalization and the vertex express route`() {
        assertTrue(build(gemini("models/gemini-2.5-flash-latest")).url.toString().contains("/models/gemini-2.5-flash:generateContent"))
        val vertex = build(gemini("gemini-3-flash-preview-vertex"))
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3-flash-preview:generateContent",
            vertex.url.toString(),
        )
        assertEquals("user", vertex.body["contents"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(TEST_KEY, vertex.headers["x-goog-api-key"])
        assertTrue(build(gemini("gemini-3-pro-preview")).url.host == "aiplatform.googleapis.com")
    }

    // ---- secrets ----

    @Test
    fun `toString of settings, built requests and secrets never shows the key`() {
        val s = openai()
        assertFalse(s.toString().contains(TEST_KEY))
        assertFalse(s.current.toString().contains(TEST_KEY))
        assertFalse(build(s).toString().contains(TEST_KEY))
        assertFalse(Secret(TEST_KEY).toString().contains(TEST_KEY))
        assertEquals(Secret(TEST_KEY), Secret(TEST_KEY))
    }

    @Test
    fun `endpoint policy errors surface as INVALID_SETTINGS`() {
        val e = assertThrows(AiException::class.java) {
            build(testSettings(endpoint = "http://api.example.com/v1/chat/completions"))
        }
        assertEquals(AiErrorKind.INVALID_SETTINGS, e.kind)
        val unacked = assertThrows(AiException::class.java) {
            build(testSettings(endpoint = "http://127.0.0.1:9/v1", mutate = { it.copy(allowInsecureLocalEndpoint = false) }))
        }
        assertEquals(AiErrorKind.INVALID_SETTINGS, unacked.kind)
        assertTrue(build(testSettings(endpoint = "http://127.0.0.1:9/v1")).insecureLocal)
    }

    @Test
    fun `config check needs enabled, key, model and endpoint`() {
        assertTrue(AiConfigCheck.isConfigured(openai()))
        assertFalse(AiConfigCheck.isConfigured(openai(mutate = { it.copy(enabled = false) })))
        assertFalse(AiConfigCheck.isConfigured(testSettings(key = "  ")))
        assertFalse(AiConfigCheck.isConfigured(testSettings(model = "").withProvider { it.copy(model = "") }))
        assertFalse(AiConfigCheck.isConfigured(testSettings(endpoint = "")))
        // defaults fill the gaps for the hosted providers
        assertTrue(AiConfigCheck.isConfigured(openai().withProvider { it.copy(model = "", endpoint = "") }))
    }
    @Test
    fun `study language is included when the prompts do not interpolate it`() {
        val user = messages(build(openai(), REQ))[0]["content"]!!.jsonPrimitive.content
        assertTrue(user.contains("Study language: en."))
    }

}
