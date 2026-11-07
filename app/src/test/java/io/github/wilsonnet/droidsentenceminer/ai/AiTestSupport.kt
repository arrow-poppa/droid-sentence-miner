package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

const val TEST_KEY = "sk-test-SECRET-key-0123456789"

class FakeSettings(var value: AiSettings) : AiSettingsSource {
    override suspend fun current(): AiSettings = value
}

/** Reversible stand-in for the Keystore cipher (the real one needs Android). */
class FakeCipher : SecretCipher {
    var lost = false
    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray() + byteArrayOf(0x42)
    override fun decrypt(blob: ByteArray): ByteArray? =
        if (lost || blob.isEmpty() || blob.last() != 0x42.toByte()) null else blob.copyOf(blob.size - 1).reversedArray()
}

/** Settings pointing [type] at [endpoint] (loopback http, acknowledged), with a key. */
fun testSettings(
    type: AiProviderType = AiProviderType.CUSTOM,
    endpoint: String = "http://localhost:1/v1/chat/completions",
    streaming: Boolean = false,
    provider: ProviderSettings = ProviderSettings(),
    key: String = TEST_KEY,
    model: String = "test-model",
    mutate: (AiSettings) -> AiSettings = { it },
): AiSettings = mutate(
    AiSettings(
        enabled = true,
        provider = type,
        streaming = streaming,
        allowInsecureLocalEndpoint = true,
        providers = mapOf(
            type to provider.copy(apiKey = Secret(key), endpoint = endpoint, model = provider.model.ifBlank { model }),
        ),
    ),
)

fun AiSettings.withProvider(block: (ProviderSettings) -> ProviderSettings): AiSettings =
    copy(providers = providers + (provider to block(current)))

val REQ = AiRequest(target = "running", sentence = "He was running late.", language = "en")

fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun <T> blocking(timeoutMs: Long = 15_000, block: suspend () -> T): T =
    runBlocking { withTimeout(timeoutMs) { block() } }

fun Flow<AiExplanationState>.collectAll(): List<AiExplanationState> = blocking { toList() }

fun jsonOk(text: String) = MockResponse()
    .setHeader("Content-Type", "application/json")
    .setBody("""{"choices":[{"message":{"content":${JsonPrimitive(text)}}}]}""")

fun sse(vararg events: String, done: Boolean = true) = MockResponse()
    .setHeader("Content-Type", "text/event-stream")
    .setBody(events.joinToString("") { "data: $it\n\n" } + if (done) "data: [DONE]\n\n" else "")

fun openAiChunk(text: String) =
    """{"choices":[{"delta":{"content":${JsonPrimitive(text)}}}]}"""

fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

fun server(block: MockWebServer.() -> Unit = {}): MockWebServer = MockWebServer().apply { block(); start() }

fun MockWebServer.endpoint(path: String = "/v1/chat/completions"): String = url(path).toString()
