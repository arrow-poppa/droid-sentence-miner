package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/** Per-call limits. `read` restarts on every received chunk, so a long answer only dies when the stream stalls. */
data class AiTimeouts(
    val connectMs: Long = 15_000,
    val readMs: Long = 30_000,
    val overallMs: Long = 120_000,
)

/**
 * Runs one provider request over OkHttp: real SSE streaming, cancellation that tears the call
 * down, status/IO errors mapped to messages without secrets, no redirects (an `https→http` or
 * cross-host redirect must never carry the key along).
 */
internal class AiTransport(client: OkHttpClient, timeouts: AiTimeouts) {
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(timeouts.connectMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.readMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(timeouts.overallMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { isLenient = true }

    /**
     * Streams when [settings] ask for it. Falls back to one non-streaming request only when the
     * provider refused the streaming request itself (HTTP 400/405/415/422/501) before any text
     * arrived; every other failure is reported as is.
     * [onPartial] receives the cumulative text.
     */
    suspend fun run(settings: AiSettings, request: AiRequest, onPartial: (String) -> Unit): String {
        val redactor = Redactor(settings.current.apiKey.value)
        if (settings.streaming) {
            val built = AiRequestBuilder.build(settings, request, stream = true)
            try {
                return execute(built, stream = true, redactor, onPartial)
            } catch (e: AiException) {
                if (!e.streamRejected) throw e
            }
        }
        val built = AiRequestBuilder.build(settings, request, stream = false)
        return execute(built, stream = false, redactor, onPartial)
    }

    private suspend fun execute(built: BuiltRequest, stream: Boolean, redactor: Redactor, onPartial: (String) -> Unit): String {
        val okRequest = try {
            Request.Builder().url(built.url).apply {
                built.headers.forEach { (k, v) -> header(k, v) }
            }.post(built.body.toString().toRequestBody(JSON_MEDIA)).build()
        } catch (_: IllegalArgumentException) {
            throw AiException(AiErrorKind.INVALID_SETTINGS, "The API key or endpoint contains characters that cannot be sent.", retryable = false)
        }
        val call = client.newCall(okRequest)
        return runCancellable(call) { response -> handle(response, built.provider, stream, redactor, onPartial) }
    }

    /** Blocking OkHttp I/O on [Dispatchers.IO]; cancelling the coroutine cancels the [Call] (and its socket). */
    private suspend fun <T> runCancellable(call: Call, block: (Response) -> T): T = coroutineScope {
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val job = coroutineContext[Job]
                // Our own cancellation (not OkHttp's call timeout, which also flags the call canceled).
                fun cancelled() = job != null && !job.isActive
                try {
                    call.execute().use(block)
                } catch (e: AiException) {
                    if (cancelled()) throw CancellationException("AI request cancelled")
                    throw e
                } catch (e: IOException) {
                    if (cancelled()) throw CancellationException("AI request cancelled")
                    throw mapIo(e)
                }
            }
        } finally {
            canceller.cancel()
        }
    }

    private fun mapIo(e: IOException): AiException = when (e) {
        is InterruptedIOException -> AiException(AiErrorKind.TIMEOUT, "The AI request timed out. Try again.", retryable = true)
        else -> AiException(AiErrorKind.NETWORK, "Could not reach the AI service. Check the connection and endpoint.", retryable = true)
    }

    private fun handle(response: Response, provider: AiProviderType, stream: Boolean, redactor: Redactor, onPartial: (String) -> Unit): String {
        if (!response.isSuccessful) throw statusError(response, redactor)
        val body = response.body ?: throw AiException(AiErrorKind.EMPTY, "The AI service returned an empty response.", retryable = true)
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        // A server that ignores `stream: true` and answers with plain JSON still gets read.
        val asStream = stream && !(contentType.contains("json") && !contentType.contains("event-stream"))
        val text = if (asStream) {
            readStream(body.source(), provider, redactor, onPartial)
        } else {
            readWhole(body.source().readByteString().utf8(), provider, redactor).also { if (it.isNotEmpty()) onPartial(it) }
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw AiException(AiErrorKind.EMPTY, "The AI service returned an empty explanation.", retryable = true)
        return trimmed
    }

    private fun readWhole(raw: String, provider: AiProviderType, redactor: Redactor): String {
        val payload = parse(raw) ?: throw AiException(AiErrorKind.OTHER, "The AI service returned an unreadable response.", retryable = true)
        AiResponses.errorOf(payload)?.let { throw payloadError(it, redactor) }
        if (provider == AiProviderType.GEMINI) {
            val text = AiResponses.geminiText(payload)
            if (text.isBlank()) {
                AiResponses.geminiBlockReason(payload)?.let {
                    throw AiException(AiErrorKind.REJECTED, "Gemini blocked the request (${redactor.redact(it, 60)}).", retryable = false)
                }
            }
            return text
        }
        return AiResponses.openAiText(payload)
    }

    private fun readStream(source: okio.BufferedSource, provider: AiProviderType, redactor: Redactor, onPartial: (String) -> Unit): String {
        val full = StringBuilder()
        var finished = false
        var failure: AiException? = null
        val parser = SseParser { data ->
            if (finished) return@SseParser
            if (data.trim() == "[DONE]") {
                finished = true
                return@SseParser
            }
            val payload = parse(data) ?: return@SseParser // keep-alive padding / malformed chunk: skip
            AiResponses.errorOf(payload)?.let {
                failure = payloadError(it, redactor)
                finished = true
                return@SseParser
            }
            val delta = if (provider == AiProviderType.GEMINI) AiResponses.geminiText(payload) else AiResponses.openAiDelta(payload)
            if (delta.isNotEmpty()) {
                full.append(delta)
                onPartial(full.toString())
            }
        }
        val buffer = ByteArray(8192)
        try {
            while (!finished) {
                val n = source.read(buffer)
                if (n < 0) break
                parser.feed(buffer, 0, n)
            }
            if (!finished) parser.finish()
        } catch (e: IOException) {
            if (full.isNotEmpty() && e !is InterruptedIOException) {
                throw AiException(AiErrorKind.NETWORK, "The connection was interrupted while the answer was streaming.", retryable = true)
            }
            throw e
        }
        failure?.let { throw it }
        return full.toString()
    }

    private fun parse(raw: String): JsonElement? = try {
        json.parseToJsonElement(raw)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun payloadError(error: Pair<Int?, String?>, redactor: Redactor): AiException {
        val (code, message) = error
        val detail = message?.let { redactor.redact(it) }.orEmpty()
        return when (code) {
            401, 403 -> AiException(AiErrorKind.INVALID_KEY, "The API key was rejected. Check it in AI settings.", retryable = false)
            429 -> AiException(AiErrorKind.RATE_LIMITED, "Rate limit or quota reached. Try again later.", retryable = true)
            in 500..599 -> AiException(AiErrorKind.SERVER, "The AI service reported an error${if (detail.isNotEmpty()) ": $detail" else ""}.", retryable = true)
            else -> AiException(AiErrorKind.REJECTED, "The AI service reported an error${if (detail.isNotEmpty()) ": $detail" else ""}.", retryable = true)
        }
    }

    private fun statusError(response: Response, redactor: Redactor): AiException {
        val code = response.code
        return when {
            code == 401 || code == 403 -> AiException(
                AiErrorKind.INVALID_KEY,
                "The API key was rejected (HTTP $code). Check it in AI settings.",
                retryable = false,
            )
            code == 429 -> AiException(AiErrorKind.RATE_LIMITED, "Rate limit or quota reached (HTTP 429). Try again later.", retryable = true)
            code == 408 -> AiException(AiErrorKind.TIMEOUT, "The AI service timed out (HTTP 408). Try again.", retryable = true)
            code in 500..599 -> AiException(AiErrorKind.SERVER, "The AI service is temporarily unavailable (HTTP $code). Try again later.", retryable = true)
            code in 300..399 -> AiException(
                AiErrorKind.INVALID_SETTINGS,
                "The endpoint redirected the request (HTTP $code). Redirects are not followed; use the final URL.",
                retryable = false,
            )
            else -> {
                val detail = try {
                    response.peekBody(2048).string().let(::parse)?.let(AiResponses::errorOf)?.second
                } catch (_: IOException) {
                    null
                }?.let { redactor.redact(it) }.orEmpty()
                AiException(
                    AiErrorKind.REJECTED,
                    "The AI service rejected the request (HTTP $code)${if (detail.isNotEmpty()) ": $detail" else ""}.",
                    retryable = false,
                    streamRejected = code in STREAM_REJECT_CODES,
                )
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val STREAM_REJECT_CODES = setOf(400, 405, 415, 422, 501)
    }
}
