package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {
    private fun parse(vararg chunks: ByteArray, finish: Boolean = true): List<String> {
        val events = mutableListOf<String>()
        val parser = SseParser { events += it }
        chunks.forEach { parser.feed(it) }
        if (finish) parser.finish()
        return events
    }

    private fun parse(vararg chunks: String, finish: Boolean = true) =
        parse(*chunks.map { it.toByteArray() }.toTypedArray(), finish = finish)

    @Test
    fun `data split in the middle of a line is joined`() {
        assertEquals(listOf("""{"a":1}"""), parse("da", "ta: {\"a\"", ":1}\n", "\n"))
    }

    @Test
    fun `utf-8 sequence split across reads decodes intact`() {
        val bytes = "data: 日本語のテスト🙂\n\n".toByteArray()
        for (cut in 1 until bytes.size) {
            assertEquals("cut=$cut", listOf("日本語のテスト🙂"), parse(bytes.copyOfRange(0, cut), bytes.copyOfRange(cut, bytes.size)))
        }
    }

    @Test
    fun `one byte at a time works`() {
        val bytes = "data: ab\r\ndata: cd\r\n\r\ndata: ef\n\n".toByteArray()
        assertEquals(listOf("ab\ncd", "ef"), parse(*bytes.map { byteArrayOf(it) }.toTypedArray()))
    }

    @Test
    fun `CRLF split between CR and LF is one line ending`() {
        assertEquals(listOf("x", "y"), parse("data: x\r", "\n\r", "\ndata: y\r\n\r\n"))
    }

    @Test
    fun `bare CR line endings`() {
        assertEquals(listOf("x"), parse("data: x\r\r"))
    }

    @Test
    fun `multi-line events join data lines with newline`() {
        assertEquals(listOf("a\nb\nc"), parse("data: a\ndata: b\ndata:c\n\n"))
    }

    @Test
    fun `comments keep-alives and other fields are ignored`() {
        assertEquals(
            listOf("real"),
            parse(": keep-alive\n\nevent: ping\nid: 7\nretry: 100\n\n: another\ndata: real\n\n"),
        )
    }

    @Test
    fun `only one leading space is stripped`() {
        assertEquals(listOf("  two"), parse("data:   two\n\n"))
    }

    @Test
    fun `DONE is delivered as a payload for the caller to interpret`() {
        assertEquals(listOf("x", "[DONE]"), parse("data: x\n\ndata: [DONE]\n\n"))
    }

    @Test
    fun `final event without blank line is delivered on finish only`() {
        assertEquals(emptyList<String>(), parse("data: tail", finish = false))
        assertEquals(listOf("tail"), parse("data: tail"))
        assertEquals(listOf("a", "tail"), parse("data: a\n\ndata: tail\n"))
    }

    @Test
    fun `blank lines without data dispatch nothing`() {
        assertEquals(emptyList<String>(), parse("\n\n\r\n\n"))
    }
    @Test(expected = AiException::class)
    fun `unterminated oversized events cannot grow without a bound`() {
        val parser = SseParser { }
        parser.feed(ByteArray(SseParser.MAX_EVENT_BYTES + 1) { 'x'.code.toByte() })
    }

}
