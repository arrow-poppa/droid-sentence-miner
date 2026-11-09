package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleDecoderTest {

    @Test
    fun detectorInputIsMultipleOf32WithinLimit() {
        assertEquals(960 to 96, PaddleDecoder.detInputSize(2000, 200, 960))
        assertEquals(640 to 480, PaddleDecoder.detInputSize(640, 480, 960))
        assertEquals(32 to 32, PaddleDecoder.detInputSize(10, 5, 960))
    }

    @Test
    fun recognizerWidthKeepsAspectAtHeight48() {
        assertEquals(480, PaddleDecoder.recWidth(400, 40))
        assertEquals(8, PaddleDecoder.recWidth(1, 100))
        assertEquals(PaddleDecoder.REC_MAX_WIDTH, PaddleDecoder.recWidth(100_000, 10))
    }

    @Test
    fun dbBoxesAreConnectedComponentsWithUnclip() {
        val w = 40
        val h = 20
        val prob = FloatArray(w * h)
        // Two text blobs: 10x4 at (2,2) and 8x4 at (25,12), plus a 1-px speck.
        for (y in 2 until 6) for (x in 2 until 12) prob[y * w + x] = 0.9f
        for (y in 12 until 16) for (x in 25 until 33) prob[y * w + x] = 0.8f
        prob[18 * w + 1] = 0.95f
        val boxes = PaddleDecoder.boxes(prob, w, h)
        assertEquals(2, boxes.size)
        val first = boxes[0]
        // unclip distance = 10*4*1.5 / (2*(10+4)) ≈ 2.14
        assertEquals(2f - 2.142857f, first.left, 1e-3f)
        assertEquals(12f + 2.142857f, first.right, 1e-3f)
        assertEquals(0.9f, first.score, 1e-5f)
        assertTrue(boxes[1].top > first.top)
    }

    @Test
    fun lowScoreBlobsAreDropped() {
        val w = 20
        val prob = FloatArray(w * 10)
        for (y in 2 until 6) for (x in 2 until 12) prob[y * w + x] = 0.4f // above thresh 0.3, below box thresh 0.6
        assertTrue(PaddleDecoder.boxes(prob, w, 10).isEmpty())
    }

    private fun probs(steps: List<Int>, classes: Int): FloatArray {
        val out = FloatArray(steps.size * classes)
        steps.forEachIndexed { t, c -> out[t * classes + c] = 0.9f }
        return out
    }

    @Test
    fun ctcCollapsesRepeatsAndDropsBlanks() {
        val chars = listOf("a", "b", "l")
        val classes = chars.size + 2 // blank + chars + space
        // b b blank a l blank l space a -> "ball a"
        val decoded = PaddleDecoder.ctcDecode(probs(listOf(2, 2, 0, 1, 3, 0, 3, 4, 1), classes), 9, classes, chars)
        assertEquals("ball a", decoded.text)
        assertEquals(0.9f, decoded.confidence!!, 1e-5f)
        assertEquals(0, decoded.chars[0].firstStep)
        assertEquals(1, decoded.chars[0].lastStep)
    }

    @Test
    fun ctcEmptyHasNoConfidence() {
        val decoded = PaddleDecoder.ctcDecode(probs(listOf(0, 0, 0), 4), 3, 4, listOf("x", "y"))
        assertEquals("", decoded.text)
        assertNull(decoded.confidence)
    }

    @Test
    fun arabicVisualOrderIsReversedButLatinRunsKeepOrder() {
        // Groups are reversed; the " 123" run (ASCII digits + space) keeps its internal order,
        // exactly like Chimahon's restorePaddleArabicText / PaddleOCR pred_reverse.
        assertEquals(" 123تبا", PaddleDecoder.restoreArabicText("ابت 123"))
        assertEquals("مرحبا", PaddleDecoder.restoreArabicText("ابحرم"))
    }

    @Test
    fun characterDictParsesPlainAndQuotedScalars() {
        val yml = """
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - ''''
              - a
              - 中
            PreProcess:
              x: 1
        """.trimIndent()
        assertEquals(listOf("!", "'", "a", "中"), PaddleDecoder.parseCharacterDict(yml))
    }
}
