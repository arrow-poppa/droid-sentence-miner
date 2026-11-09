package io.github.wilsonnet.droidsentenceminer.ai

import java.io.ByteArrayOutputStream

/**
 * Incremental `text/event-stream` parser (port of `_readSseStream` in ai-provider.js).
 *
 * It splits on raw bytes: `\n` and `\r` never occur inside a multi-byte UTF-8 sequence, so a
 * network read that ends in the middle of a character (or in the middle of a line, or between
 * the `\r` and `\n` of a CRLF) is carried over to the next [feed] and decoded only once the
 * line is complete. Per event the `data:` lines are joined with `\n`; `event:`, `id:`, `retry:`
 * and `: comment` keep-alive lines carry no text and are ignored. An event the server never
 * terminated with a blank line (some gateways just close the stream) is delivered by [finish].
 */
class SseParser(private val onEvent: (String) -> Unit) {
    private val line = ByteArrayOutputStream()
    private val data = ArrayList<String>()
    private var lastWasCr = false
    private var eventBytes = 0

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (i in offset until offset + length) {
            if (++eventBytes > MAX_EVENT_BYTES) {
                throw AiException(AiErrorKind.OTHER, "The AI service returned an oversized stream event.", retryable = false)
            }
            val b = bytes[i]
            when (b) {
                '\n'.code.toByte() -> {
                    if (lastWasCr) { // second half of CRLF: the line already ended at the CR
                        lastWasCr = false
                    } else {
                        endLine()
                    }
                }
                '\r'.code.toByte() -> {
                    endLine()
                    lastWasCr = true
                }
                else -> {
                    lastWasCr = false
                    line.write(b.toInt())
                }
            }
        }
    }

    /** Flushes a trailing line and event that were not followed by a blank line. */
    fun finish() {
        if (line.size() > 0) endLine()
        dispatch()
    }

    private fun endLine() {
        if (line.size() == 0) {
            dispatch()
            return
        }
        val text = line.toString(Charsets.UTF_8.name())
        line.reset()
        if (text.startsWith("data:")) {
            data += text.substring(5).removePrefix(" ")
        }
        // "event:", "id:", "retry:" and ":comment" lines are intentionally dropped.
    }

    private fun dispatch() {
        eventBytes = 0
        if (data.isEmpty()) return
        val payload = data.joinToString("\n")
        data.clear()
        onEvent(payload)
    }

    companion object {
        const val MAX_EVENT_BYTES = 1024 * 1024
    }
}
