package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class AiExplanationServiceTest {
    private val servers = mutableListOf<MockWebServer>()

    @After fun tearDown() { servers.forEach { runCatching { it.shutdown() } } }

    private fun srv(block: MockWebServer.() -> Unit = {}) = server(block).also { servers += it }

    private fun service(
        settings: FakeSettings,
        client: OkHttpClient = OkHttpClient(),
        timeouts: AiTimeouts = AiTimeouts(),
        cacheSize: Int = 64,
    ) = AiExplanationService(settings, client, timeouts, testScope(), cacheSize)

    private fun last(states: List<AiExplanationState>) = states.last()

    private fun done(text: String, cached: Boolean = false) = AiExplanationState.Done(text, cached)

    private fun error(state: AiExplanationState): AiExplanationState.Error = state as AiExplanationState.Error

    // ---- providers over the wire ----

    @Test
    fun `OpenAI request json and plain response`() {
        val server = srv { enqueue(jsonOk("  An explanation.\n")) }
        val settings = FakeSettings(
            testSettings(AiProviderType.OPENAI, server.endpoint(), model = "gpt-4o-mini", provider = ProviderSettings()),
        )
        val states = service(settings).explain(REQ).collectAll()
        assertEquals(done("An explanation."), last(states))
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/v1/chat/completions", rec.path)
        assertEquals("Bearer $TEST_KEY", rec.getHeader("Authorization"))
        assertTrue(rec.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = rec.bodyJson()
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertFalse("stream" in body)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("'running'"))
    }

    @Test
    fun `OpenAI streaming sets stream true and returns the joined text`() {
        val server = srv { enqueue(sse(openAiChunk("An "), openAiChunk("expl"), openAiChunk("anation."))) }
        val settings = FakeSettings(testSettings(AiProviderType.OPENAI, server.endpoint(), streaming = true))
        val states = service(settings).explain(REQ).collectAll()
        assertEquals(done("An explanation."), last(states))
        assertTrue(states.dropLast(1).all { it is AiExplanationState.Loading || it is AiExplanationState.Streaming })
        val rec = server.takeRequest()
        assertEquals("true", rec.bodyJson()["stream"]!!.jsonPrimitive.content)
        assertEquals("text/event-stream", rec.getHeader("Accept"))
    }

    @Test
    fun `streaming emits cumulative partial text progressively`() {
        val server = srv { enqueue(sse(openAiChunk("A"), openAiChunk("B"), openAiChunk("C"))) }
        val partials = mutableListOf<String>()
        val text = blocking {
            AiTransport(OkHttpClient(), AiTimeouts()).run(
                testSettings(AiProviderType.OPENAI, server.endpoint(), streaming = true), REQ,
            ) { partials += it }
        }
        assertEquals("ABC", text)
        assertEquals(listOf("A", "AB", "ABC"), partials)
    }

    @Test
    fun `slow streaming is observable as Streaming states before Done`() {
        val body = (1..6).joinToString("") { "data: ${openAiChunk("w$it ")}\n\n" } + "data: [DONE]\n\n"
        val server = srv { enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(40, 60, TimeUnit.MILLISECONDS)) }
        val states = service(FakeSettings(testSettings(AiProviderType.OPENAI, server.endpoint(), streaming = true))).explain(REQ).collectAll()
        val streaming = states.filterIsInstance<AiExplanationState.Streaming>()
        assertTrue("saw ${states.size} states", streaming.isNotEmpty())
        assertEquals(done("w1 w2 w3 w4 w5 w6"), last(states))
        assertTrue(streaming.all { "w1 w2 w3 w4 w5 w6".startsWith(it.text.trim()) })
    }

    @Test
    fun `Gemini non-streaming uses the key header and generateContent`() {
        val server = srv {
            enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}"""))
        }
        val s = testSettings(AiProviderType.GEMINI, server.url("/v1beta/models").toString(), model = "gemini-2.5-flash")
            .let { it.copy(systemPrompt = "be brief") }
        assertEquals(done("hello"), last(service(FakeSettings(s)).explain(REQ).collectAll()))
        val rec = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", rec.path)
        assertEquals(TEST_KEY, rec.getHeader("x-goog-api-key"))
        assertNull(rec.getHeader("Authorization"))
        assertFalse(rec.requestUrl.toString().contains(TEST_KEY))
        val body = rec.bodyJson()
        assertEquals("be brief", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Gemini SSE is parsed, thought parts are skipped, chunks arrive progressively`() {
        val chunks = listOf(
            """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"lo ", "thought":false},{"text":"there"}]}}]}""",
        )
        val server = srv { enqueue(sse(*chunks.toTypedArray(), done = false)) }
        val partials = mutableListOf<String>()
        val s = testSettings(AiProviderType.GEMINI, server.url("/v1beta/models").toString(), streaming = true, model = "gemini-2.5-flash")
        val text = blocking { AiTransport(OkHttpClient(), AiTimeouts()).run(s, REQ) { partials += it } }
        assertEquals("Hello there", text)
        assertEquals(listOf("Hel", "Hello there"), partials)
        val rec = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse", rec.path)
        assertEquals(TEST_KEY, rec.getHeader("x-goog-api-key"))
    }

    @Test
    fun `Gemini blocked prompt becomes a clear error`() {
        val server = srv {
            enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        }
        val s = testSettings(AiProviderType.GEMINI, server.url("/v1beta/models").toString(), model = "gemini-2.5-flash")
        val e = error(last(service(FakeSettings(s)).explain(REQ).collectAll()))
        assertEquals(AiErrorKind.REJECTED, e.kind)
        assertTrue(e.message, e.message.contains("SAFETY"))
    }

    @Test
    fun `DeepSeek request carries thinking and reasoning params`() {
        val server = srv { enqueue(jsonOk("ok")) }
        val s = testSettings(
            AiProviderType.DEEPSEEK, server.endpoint("/chat/completions"),
            provider = ProviderSettings(model = "deepseek-reasoner", thinkingMode = "enabled", thinkingIntensity = "high"),
        )
        assertEquals(done("ok"), last(service(FakeSettings(s)).explain(REQ).collectAll()))
        val body = server.takeRequest().bodyJson()
        assertEquals("deepseek-reasoner", body["model"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OpenRouter request has the provider routing object and attribution headers`() {
        val server = srv { enqueue(jsonOk("ok")) }
        val s = testSettings(
            AiProviderType.OPENROUTER, server.endpoint("/api/v1/chat/completions"),
            provider = ProviderSettings(model = "openai/gpt-4o-mini", routingMode = "order", providerSlugs = "deepinfra, together", allowFallbacks = false, thinkingIntensity = "max"),
        )
        assertEquals(done("ok"), last(service(FakeSettings(s)).explain(REQ).collectAll()))
        val rec = server.takeRequest()
        val body = rec.bodyJson()
        val provider = body["provider"]!!.jsonObject
        assertEquals(listOf("deepinfra", "together"), provider["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", provider["allow_fallbacks"]!!.jsonPrimitive.content)
        assertEquals("xhigh", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("true", body["reasoning"]!!.jsonObject["exclude"]!!.jsonPrimitive.content)
        assertEquals(AiRequestBuilder.APP_TITLE, rec.getHeader("X-OpenRouter-Title"))
    }

    @Test
    fun `custom endpoint with custom body and forced streaming`() {
        val server = srv { enqueue(sse(openAiChunk("hi"))) }
        val s = testSettings(
            AiProviderType.CUSTOM, server.endpoint("/llm/v1/chat/completions"), streaming = true,
            provider = ProviderSettings(requestBodyJson = """{"top_p":0.5,"stream":false,"extra":{"a":1}}"""),
        )
        assertEquals(done("hi"), last(service(FakeSettings(s)).explain(REQ).collectAll()))
        val rec = server.takeRequest()
        assertEquals("/llm/v1/chat/completions", rec.path)
        val body = rec.bodyJson()
        assertEquals("0.5", body["top_p"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("1", body["extra"]!!.jsonObject["a"]!!.jsonPrimitive.content)
    }

    // ---- SSE edge cases over the wire ----

    @Test
    fun `multi-byte text survives tiny network chunks`() {
        val text = "日本語のテスト🙂です"
        val server = srv {
            enqueue(sse(openAiChunk(text.substring(0, 4)), openAiChunk(text.substring(4))).throttleBody(2, 1, TimeUnit.MILLISECONDS))
        }
        val states = service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint()))).explain(REQ).collectAll()
        assertEquals(done(text), last(states))
    }

    @Test
    fun `events after DONE are ignored`() {
        val server = srv { enqueue(sse(openAiChunk("A"), "[DONE]", openAiChunk("B"), done = false)) }
        assertEquals(done("A"), last(service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint()))).explain(REQ).collectAll()))
    }

    @Test
    fun `malformed chunks and keep-alives are skipped`() {
        val body = ": keep-alive\n\ndata: {not json\n\ndata: ${openAiChunk("A")}\n\nevent: ping\ndata: \n\ndata: ${openAiChunk("B")}\n\ndata: [DONE]\n\n"
        val server = srv { enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)) }
        assertEquals(done("AB"), last(service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint()))).explain(REQ).collectAll()))
    }

    @Test
    fun `CRLF streams and an unterminated final event are handled`() {
        val body = "data: ${openAiChunk("A")}\r\n\r\ndata: ${openAiChunk("B")}"
        val server = srv { enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)) }
        assertEquals(done("AB"), last(service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint()))).explain(REQ).collectAll()))
    }

    @Test
    fun `an error object mid-stream becomes an error and is not cached`() {
        val server = srv {
            enqueue(sse(openAiChunk("A"), """{"error":{"message":"overloaded","code":503}}""", done = false))
            enqueue(sse(openAiChunk("fine")))
        }
        val svc = service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint())))
        val states = svc.explain(REQ).collectAll()
        val e = error(last(states))
        assertEquals(AiErrorKind.SERVER, e.kind)
        assertTrue(e.retryable)
        assertTrue(states.none { it is AiExplanationState.Done })
        assertEquals(done("fine"), last(svc.explain(REQ).collectAll()))
    }

    @Test
    fun `a mid-stream error that echoes the key is redacted`() {
        val server = srv { enqueue(sse("""{"error":{"message":"bad key $TEST_KEY for you","code":400}}""", done = false)) }
        val e = error(last(service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint()))).explain(REQ).collectAll()))
        assertFalse(e.message, e.message.contains(TEST_KEY))
        assertTrue(e.message, e.message.contains("***"))
    }

    @Test
    fun `a server that ignores stream and answers JSON is still read, with one request`() {
        val server = srv { enqueue(jsonOk("whole")) }
        val svc = service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint())))
        assertEquals(done("whole"), last(svc.explain(REQ).collectAll()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `provider rejecting stream falls back to one non-streaming request`() {
        val server = srv {
            enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("""{"error":{"message":"stream not supported"}}"""))
            enqueue(jsonOk("plain"))
        }
        val svc = service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint())))
        assertEquals(done("plain"), last(svc.explain(REQ).collectAll()))
        assertEquals(2, server.requestCount)
        assertEquals("true", server.takeRequest().bodyJson()["stream"]!!.jsonPrimitive.content)
        assertFalse("stream" in server.takeRequest().bodyJson())
    }

    @Test
    fun `other errors do not trigger a non-streaming retry`() {
        for (code in listOf(401, 403, 404, 429, 500, 503)) {
            val server = srv { enqueue(MockResponse().setResponseCode(code)); enqueue(jsonOk("should not be used")) }
            val svc = service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint())))
            assertTrue("$code", last(svc.explain(REQ).collectAll()) is AiExplanationState.Error)
            assertEquals("$code", 1, server.requestCount)
        }
    }

    // ---- errors ----

    @Test
    fun `status codes map to clear messages without secrets`() {
        data class Case(val code: Int, val kind: AiErrorKind, val retryable: Boolean, val mention: String)
        val cases = listOf(
            Case(401, AiErrorKind.INVALID_KEY, false, "API key"),
            Case(403, AiErrorKind.INVALID_KEY, false, "API key"),
            Case(429, AiErrorKind.RATE_LIMITED, true, "Rate limit"),
            Case(500, AiErrorKind.SERVER, true, "unavailable"),
            Case(503, AiErrorKind.SERVER, true, "unavailable"),
            Case(404, AiErrorKind.REJECTED, false, "rejected"),
        )
        for (c in cases) {
            val server = srv {
                enqueue(
                    MockResponse().setResponseCode(c.code).setHeader("Content-Type", "application/json")
                        .setBody("""{"error":{"message":"Incorrect API key provided: $TEST_KEY. Authorization: Bearer $TEST_KEY"}}"""),
                )
            }
            val e = error(last(service(FakeSettings(testSettings(endpoint = server.endpoint()))).explain(REQ).collectAll()))
            assertEquals("${c.code}", c.kind, e.kind)
            assertEquals("${c.code}", c.retryable, e.retryable)
            assertTrue("${c.code}: ${e.message}", e.message.contains(c.mention))
            assertFalse("${c.code}: ${e.message}", e.message.contains(TEST_KEY))
            assertFalse("${c.code}: ${e.message}", e.message.contains("Bearer", ignoreCase = true))
            assertFalse("${c.code}: ${e.message}", e.message.contains("Authorization", ignoreCase = true))
        }
    }

    @Test
    fun `a 400 detail from the provider is shown, with the key redacted`() {
        val server = srv {
            enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"model 'x' not found (key $TEST_KEY)"}}"""))
        }
        val e = error(last(service(FakeSettings(testSettings(endpoint = server.endpoint()))).explain(REQ).collectAll()))
        assertTrue(e.message, e.message.contains("model 'x' not found"))
        assertFalse(e.message, e.message.contains(TEST_KEY))
    }

    @Test
    fun `empty answers are an error`() {
        val plain = srv { enqueue(jsonOk("  ")) }
        assertEquals(AiErrorKind.EMPTY, error(last(service(FakeSettings(testSettings(endpoint = plain.endpoint()))).explain(REQ).collectAll())).kind)
        val streamed = srv { enqueue(sse()) }
        assertEquals(AiErrorKind.EMPTY, error(last(service(FakeSettings(testSettings(streaming = true, endpoint = streamed.endpoint()))).explain(REQ).collectAll())).kind)
        val noChoices = srv { enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[]}""")) }
        assertEquals(AiErrorKind.EMPTY, error(last(service(FakeSettings(testSettings(endpoint = noChoices.endpoint()))).explain(REQ).collectAll())).kind)
    }

    @Test
    fun `OpenAI-compatible content part arrays and top-level errors are handled`() {
        val parts = srv { enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":[{"text":"A"},{"content":"B"},"C"]}}]}""")) }
        assertEquals(done("ABC"), last(service(FakeSettings(testSettings(endpoint = parts.endpoint()))).explain(REQ).collectAll()))
        val err = srv { enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"error":{"message":"upstream failed","code":502}}""")) }
        val e = error(last(service(FakeSettings(testSettings(endpoint = err.endpoint()))).explain(REQ).collectAll()))
        assertEquals(AiErrorKind.SERVER, e.kind)
    }

    @Test
    fun `a stalled server times out`() {
        val server = srv { enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())), timeouts = AiTimeouts(connectMs = 500, readMs = 300, overallMs = 2_000))
        val e = error(last(svc.explain(REQ).collectAll()))
        assertEquals(AiErrorKind.TIMEOUT, e.kind)
        assertTrue(e.retryable)
    }

    @Test
    fun `the overall call timeout bounds a trickling stream`() {
        val body = (1..200).joinToString("") { "data: ${openAiChunk("x")}\n\n" }
        val server = srv { enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(30, 100, TimeUnit.MILLISECONDS)) }
        val svc = service(
            FakeSettings(testSettings(streaming = true, endpoint = server.endpoint())),
            timeouts = AiTimeouts(readMs = 5_000, overallMs = 500),
        )
        assertEquals(AiErrorKind.TIMEOUT, error(last(svc.explain(REQ).collectAll())).kind)
    }

    @Test
    fun `an unreachable endpoint is a network error`() {
        val port = srv().port.also { servers.last().shutdown() }
        val e = error(last(service(FakeSettings(testSettings(endpoint = "http://localhost:$port/v1"))).explain(REQ).collectAll()))
        assertEquals(AiErrorKind.NETWORK, e.kind)
        assertTrue(e.retryable)
    }

    @Test
    fun `redirects are not followed`() {
        val server = srv {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/other"))
            enqueue(jsonOk("leaked"))
        }
        val e = error(last(service(FakeSettings(testSettings(endpoint = server.endpoint()))).explain(REQ).collectAll()))
        assertEquals(AiErrorKind.INVALID_SETTINGS, e.kind)
        assertEquals(1, server.requestCount)
    }

    // ---- configuration gates: nothing is sent ----

    @Test
    fun `nothing is sent when disabled or without key`() {
        val server = srv { enqueue(jsonOk("x")) }
        val ok = testSettings(endpoint = server.endpoint())
        assertEquals(listOf(AiExplanationState.NotConfigured), service(FakeSettings(ok.copy(enabled = false))).explain(REQ).collectAll())
        assertEquals(listOf(AiExplanationState.NotConfigured), service(FakeSettings(testSettings(endpoint = server.endpoint(), key = ""))).explain(REQ).collectAll())
        assertEquals(listOf(AiExplanationState.NotConfigured), service(FakeSettings(testSettings(endpoint = ""))).explain(REQ).collectAll())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `automatic explanation honours the autoExplain setting`() {
        val server = srv { enqueue(jsonOk("auto")) }
        val settings = FakeSettings(testSettings(endpoint = server.endpoint()))
        val svc = service(settings)
        assertEquals(listOf(AiExplanationState.Idle), svc.explainAutomatically(REQ).collectAll())
        assertEquals(0, server.requestCount)
        settings.value = settings.value.copy(autoExplain = true)
        assertEquals(done("auto"), last(svc.explainAutomatically(REQ).collectAll()))
        assertEquals(1, server.requestCount)
        // explicit tap works with auto off
        settings.value = settings.value.copy(autoExplain = false)
        assertEquals(done("auto", cached = true), last(svc.explain(REQ).collectAll()))
    }

    @Test
    fun `http endpoints are rejected except acknowledged loopback or LAN`() {
        val server = srv { enqueue(jsonOk("x")) }
        val publicHttp = service(FakeSettings(testSettings(endpoint = "http://api.example.com/v1/chat/completions")))
        val e1 = error(last(publicHttp.explain(REQ).collectAll()))
        assertEquals(AiErrorKind.INVALID_SETTINGS, e1.kind)
        assertFalse(e1.retryable)
        val unacked = service(FakeSettings(testSettings(endpoint = server.endpoint(), mutate = { it.copy(allowInsecureLocalEndpoint = false) })))
        val e2 = error(last(unacked.explain(REQ).collectAll()))
        assertEquals(AiErrorKind.INVALID_SETTINGS, e2.kind)
        assertTrue(e2.message, e2.message.contains("insecure local endpoint", ignoreCase = true))
        assertEquals(0, server.requestCount)
        val acked = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        assertEquals(done("x"), last(acked.explain(REQ).collectAll()))
    }

    @Test
    fun `invalid custom body json sends nothing`() {
        val server = srv { enqueue(jsonOk("x")) }
        val s = testSettings(endpoint = server.endpoint(), provider = ProviderSettings(requestBodyJson = "{oops"))
        val e = error(last(service(FakeSettings(s)).explain(REQ).collectAll()))
        assertEquals(AiErrorKind.INVALID_SETTINGS, e.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a key with control characters is refused instead of crashing`() {
        val server = srv { enqueue(jsonOk("x")) }
        val e = error(last(service(FakeSettings(testSettings(endpoint = server.endpoint(), key = "abc\ndef"))).explain(REQ).collectAll()))
        assertEquals(AiErrorKind.INVALID_SETTINGS, e.kind)
        assertFalse(e.message.contains("abc"))
        assertEquals(0, server.requestCount)
    }

    // ---- cancellation ----

    @Test
    fun `cancelling the collector cancels the OkHttp call`() {
        val canceled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).build()
        val body = (1..100).joinToString("") { "data: ${openAiChunk("x")}\n\n" }
        val server = srv { enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(20, 1, TimeUnit.SECONDS)) }
        val svc = service(FakeSettings(testSettings(streaming = true, endpoint = server.endpoint())), client = client)
        blocking {
            val job = CoroutineScope(Dispatchers.IO).launch { svc.explain(REQ).collect { } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            delay(300)
            job.cancelAndJoin()
        }
        assertTrue("OkHttp call was not cancelled", canceled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `cancelled requests are not cached`() {
        val server = srv {
            enqueue(jsonOk("late").setBodyDelay(400, TimeUnit.MILLISECONDS))
            enqueue(jsonOk("second"))
        }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        blocking {
            val job = CoroutineScope(Dispatchers.IO).launch { svc.explain(REQ).collect { } }
            server.takeRequest(5, TimeUnit.SECONDS)
            job.cancelAndJoin()
            delay(600)
            assertNull(svc.cached(REQ))
        }
        assertEquals(done("second"), last(svc.explain(REQ).collectAll()))
    }

    // ---- cache ----

    @Test
    fun `second identical request is served from the cache without a network call`() {
        val server = srv { enqueue(jsonOk("cached text")) }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        assertEquals(done("cached text"), last(svc.explain(REQ).collectAll()))
        assertEquals(listOf(done("cached text", cached = true)), svc.explain(REQ).collectAll())
        assertEquals(1, server.requestCount)
        assertEquals(done("cached text", cached = true), blocking { svc.cached(REQ) })
    }

    @Test
    fun `changing model, prompts, options or the request misses the cache`() {
        val server = srv { repeat(40) { enqueue(jsonOk("r$it")) } }
        val settings = FakeSettings(testSettings(endpoint = server.endpoint()))
        val svc = service(settings)
        val base = settings.value
        svc.explain(REQ).collectAll()
        assertEquals(1, server.requestCount)

        val mutations: List<Pair<String, (AiSettings) -> AiSettings>> = listOf(
            "model" to { s -> s.withProvider { it.copy(model = "another-model") } },
            "system prompt" to { s -> s.copy(systemPrompt = "Be terse.") },
            "user prompt" to { s -> s.copy(userPrompt = "Define {{target}} in {{sentence}}.") },
            "temperature" to { s -> s.copy(temperature = 1.5) },
            "reasoning" to { s -> s.withProvider { it.copy(thinkingMode = "enabled", thinkingIntensity = "high") } },
            "custom body" to { s -> s.withProvider { it.copy(requestBodyJson = """{"top_p":0.1}""") } },
        )
        var expected = 1
        for ((name, mutate) in mutations) {
            settings.value = mutate(base)
            svc.explain(REQ).collectAll()
            expected++
            assertEquals(name, expected, server.requestCount)
            settings.value = base
            assertEquals("$name: original still cached", done("r0", cached = true), last(svc.explain(REQ).collectAll()))
            assertEquals(name, expected, server.requestCount)
        }
        for ((name, other) in listOf(
            "target" to REQ.copy(target = "run"),
            "sentence" to REQ.copy(sentence = "Another sentence."),
            "language" to REQ.copy(language = "fr"),
        )) {
            svc.explain(other).collectAll()
            expected++
            assertEquals(name, expected, server.requestCount)
        }
    }

    @Test
    fun `toggling streaming or the key does not miss the cache`() {
        val server = srv { enqueue(jsonOk("kept")); enqueue(jsonOk("unexpected")) }
        val settings = FakeSettings(testSettings(endpoint = server.endpoint(), streaming = false))
        val svc = service(settings)
        svc.explain(REQ).collectAll()
        settings.value = settings.value.copy(streaming = true)
        assertEquals(done("kept", cached = true), last(svc.explain(REQ).collectAll()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `forceRefresh bypasses the cache and replaces the entry`() {
        val server = srv { enqueue(jsonOk("v1")); enqueue(jsonOk("v2")) }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        assertEquals(done("v1"), last(svc.explain(REQ).collectAll()))
        assertEquals(done("v2"), last(svc.explain(REQ, forceRefresh = true).collectAll()))
        assertEquals(done("v2", cached = true), last(svc.explain(REQ).collectAll()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the cache is a bounded LRU`() {
        val server = srv { repeat(10) { enqueue(jsonOk("t$it")) } }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())), cacheSize = 2)
        val a = REQ.copy(sentence = "A")
        val b = REQ.copy(sentence = "B")
        val c = REQ.copy(sentence = "C")
        svc.explain(a).collectAll(); svc.explain(b).collectAll()
        svc.explain(a).collectAll() // touch A: B is now the eldest
        svc.explain(c).collectAll() // evicts B
        assertEquals(3, server.requestCount)
        assertEquals(done("t0", cached = true), last(svc.explain(a).collectAll()))
        assertEquals(3, server.requestCount)
        svc.explain(b).collectAll()
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `failures are not cached and clearCache empties it`() {
        val server = srv { enqueue(MockResponse().setResponseCode(500)); enqueue(jsonOk("ok")); enqueue(jsonOk("again")) }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        assertTrue(last(svc.explain(REQ).collectAll()) is AiExplanationState.Error)
        assertEquals(done("ok"), last(svc.explain(REQ).collectAll()))
        svc.clearCache()
        assertEquals(done("again"), last(svc.explain(REQ).collectAll()))
    }

    // ---- concurrency & stale guard ----

    @Test
    fun `identical concurrent requests share one in-flight call`() {
        val server = srv { enqueue(jsonOk("shared").setBodyDelay(500, TimeUnit.MILLISECONDS)); enqueue(jsonOk("unexpected")) }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        val results = blocking {
            val first = async(Dispatchers.IO) { svc.explain(REQ).toList() }
            delay(150)
            val second = async(Dispatchers.IO) { svc.explain(REQ).toList() }
            listOf(first.await(), second.await())
        }
        results.forEach { assertEquals(done("shared"), last(it)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `one collector leaving does not cancel a shared call`() {
        val server = srv { enqueue(jsonOk("shared").setBodyDelay(500, TimeUnit.MILLISECONDS)) }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        val survivor = blocking {
            val leaving = CoroutineScope(Dispatchers.IO).launch { svc.explain(REQ).collect { } }
            delay(100)
            val staying = async(Dispatchers.IO) { svc.explain(REQ).toList() }
            delay(100)
            leaving.cancelAndJoin()
            staying.await()
        }
        assertEquals(done("shared"), last(survivor))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `forceRefresh supersedes an in-flight call and the old answer never surfaces`() {
        val server = srv {
            enqueue(jsonOk("old").setBodyDelay(700, TimeUnit.MILLISECONDS))
            enqueue(jsonOk("new"))
        }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        val oldStates = CopyOnWriteArrayList<AiExplanationState>()
        val newStates = blocking {
            val oldJob = CoroutineScope(Dispatchers.IO).launch { svc.explain(REQ).collect { oldStates += it } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val forced = svc.explain(REQ, forceRefresh = true).toList()
            oldJob.join() // the superseded collector completes by itself
            delay(900) // longer than the old response delay
            forced
        }
        assertEquals(done("new"), last(newStates))
        assertTrue("old collector saw $oldStates", oldStates.none { it is AiExplanationState.Done || it is AiExplanationState.Error })
        assertEquals(done("new", cached = true), last(svc.explain(REQ).collectAll()))
        assertEquals(2, server.requestCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a newer word never shows the older word's response`() {
        val server = srv {
            enqueue(jsonOk("answer for A").setBodyDelay(600, TimeUnit.MILLISECONDS))
            enqueue(jsonOk("answer for B"))
        }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())))
        val requests = MutableSharedFlow<AiRequest>(extraBufferCapacity = 4)
        val seen = CopyOnWriteArrayList<Pair<String, AiExplanationState>>()
        val a = REQ.copy(sentence = "A")
        val b = REQ.copy(sentence = "B")
        blocking {
            val job = CoroutineScope(Dispatchers.IO).launch {
                requests.collectLatest { r -> svc.explain(r).collect { seen += r.sentence to it } }
            }
            delay(100)
            requests.emit(a)
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            requests.emit(b)
            delay(1_000)
            job.cancelAndJoin()
        }
        assertTrue(seen.none { it.first == "A" && (it.second is AiExplanationState.Done) })
        assertEquals("B" to done("answer for B"), seen.last())
        assertNull(blocking { svc.cached(a) })
    }

    @Test
    fun `cancelAll stops every in-flight request`() {
        val canceled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
        }).build()
        val server = srv { enqueue(jsonOk("never").setBodyDelay(20, TimeUnit.SECONDS)) }
        val svc = service(FakeSettings(testSettings(endpoint = server.endpoint())), client = client)
        val states = CopyOnWriteArrayList<AiExplanationState>()
        blocking {
            val job = CoroutineScope(Dispatchers.IO).launch { svc.explain(REQ).collect { states += it } }
            server.takeRequest(5, TimeUnit.SECONDS)
            svc.cancelAll()
            job.join()
        }
        assertTrue(canceled.await(5, TimeUnit.SECONDS))
        assertTrue(states.none { it is AiExplanationState.Done })
    }
}
