package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OcrGeometryTest {
    @Test
    fun upscaledLineAndSymbolBoxesReturnToSourceCrop() {
        val line = OcrLine("Hi", BoundingBox(20, 40, 100, 80), chars = listOf(BoundingBox(20, 40, 60, 80), BoundingBox(60, 40, 100, 80)))
        val source = OcrGeometry.toSource(line, 400, 200, 200, 100)
        assertEquals(BoundingBox(10, 20, 50, 40), source.box)
        assertEquals(listOf(BoundingBox(10, 20, 30, 40), BoundingBox(30, 20, 50, 40)), source.chars)
        assertEquals("Hi", source.text)
    }

    @Test
    fun independentRoundedScaleFactorsStayInsideSourceImage() {
        val line = OcrLine("x", BoundingBox(-1, 1, 1002, 501), confidence = 0.8f)
        val mapped = OcrGeometry.toSource(line, 1000, 500, 333, 167)
        assertEquals(BoundingBox(0, 0, 333, 167), mapped.box)
        assertEquals(0.8f, mapped.confidence)
    }

    @Test
    fun unchangedImageRetainsOriginalRead() {
        val line = OcrLine("text", BoundingBox(0, 0, 10, 10))
        assertSame(line, OcrGeometry.toSource(line, 100, 100, 100, 100))
    }

    private fun word(text: String, left: Int, widths: List<Int>): OcrGeometry.WordBoxes {
        var x = left
        val symbols = text.mapIndexed { i, c -> c.toString() to BoundingBox(x, 10, x + widths[i], 30).also { x += widths[i] } }
        return OcrGeometry.WordBoxes(text, BoundingBox(left, 10, x, 30), symbols)
    }

    @Test
    fun alignedCharsKeepSpacesSoTapsIndexTheLineText() {
        // "I am": a narrow I, then a space ML Kit reports no symbol for, then "am".
        val words = listOf(word("I", 0, listOf(4)), word("am", 20, listOf(12, 18)))
        val chars = OcrGeometry.alignChars("I am", words, BoundingBox(0, 10, 50, 30))!!
        assertEquals(4, chars.size)
        assertEquals(BoundingBox(0, 10, 4, 30), chars[0])
        assertEquals(BoundingBox(4, 10, 20, 30), chars[1]) // the gap between the words
        assertEquals(BoundingBox(20, 10, 32, 30), chars[2])
        assertEquals(BoundingBox(32, 10, 50, 30), chars[3])
    }

    @Test
    fun wordWithMismatchedSymbolsIsSplitEvenlyAndMissingWordGivesNull() {
        val odd = OcrGeometry.WordBoxes("ab", BoundingBox(0, 0, 20, 10), listOf("ab" to BoundingBox(0, 0, 5, 10), "c" to BoundingBox(5, 0, 9, 10)))
        val chars = OcrGeometry.alignChars("ab", listOf(odd), BoundingBox(0, 0, 20, 10))!!
        assertEquals(listOf(BoundingBox(0, 0, 10, 10), BoundingBox(10, 0, 20, 10)), chars)
        org.junit.Assert.assertNull(OcrGeometry.alignChars("ab", listOf(word("xy", 0, listOf(5, 5))), BoundingBox(0, 0, 20, 10)))
    }
}
