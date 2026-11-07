package io.github.wilsonnet.droidsentenceminer.capture

import io.github.wilsonnet.droidsentenceminer.ocr.BoundingBox
import io.github.wilsonnet.droidsentenceminer.ocr.OcrLine
import io.github.wilsonnet.droidsentenceminer.ocr.TextProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextMultilingualTest {

    private val area = TextArea(0f, 0.8f, 1f, 1f)

    @Test
    fun japaneseProfileStillDropsLinesWithoutJapanese() {
        val english = OcrLine("Hello world", BoundingBox(100, 100, 600, 160))
        assertTrue(ScreenText.fromRead(listOf(english), listOf(english.box), 1000, 200, area, 0).isEmpty())
    }

    @Test
    fun latinProfileKeepsEnglishLines() {
        val english = OcrLine("Hello world", BoundingBox(100, 100, 600, 160))
        val lines = ScreenText.fromRead(listOf(english), listOf(english.box), 1000, 200, area, 0, TextProfile.LATIN)
        assertEquals("Hello world", lines.single().text)
        assertEquals("Hello world".length, lines.single().glyphs.size)
    }

    @Test
    fun cyrillicProfileKeepsRussianAndDropsDigitsOnly() {
        val russian = OcrLine("Она говорила", BoundingBox(100, 100, 600, 160))
        val counter = OcrLine("12:30", BoundingBox(700, 100, 800, 160))
        val lines = ScreenText.fromRead(listOf(russian, counter), listOf(russian.box, counter.box), 1000, 200, area, 0, TextProfile.CYRILLIC)
        assertEquals(listOf("Она говорила"), lines.map { it.text })
    }

    @Test
    fun arabicEvenSplitRunsRightToLeft() {
        val arabic = OcrLine("مرحبا", BoundingBox(100, 100, 600, 160))
        val line = ScreenText.fromRead(listOf(arabic), listOf(arabic.box), 1000, 200, area, 0, TextProfile.ARABIC).single()
        // The first logical character is drawn at the right edge.
        assertTrue(line.glyphs.first().left > line.glyphs.last().left)
        assertEquals(0.6f, line.glyphs.first().right, 1e-4f)
    }

    @Test
    fun tallLatinBoxIsNotReadAsVerticalText() {
        val word = OcrLine("Hi", BoundingBox(100, 20, 130, 180))
        val line = ScreenText.fromRead(listOf(word), listOf(word.box), 1000, 200, area, 0, TextProfile.LATIN).single()
        assertEquals(line.glyphs[0].top, line.glyphs[1].top, 1e-6f) // side by side, not stacked
    }
    @Test
    fun missingConfidenceAndOriginalUnicodeAreRetained() {
        val raw = OcrLine("ﬁne café", BoundingBox(0, 0, 100, 40))
        val line = ScreenText.fromRead(listOf(raw), listOf(raw.box), 100, 40, area, 0, TextProfile.LATIN).single()
        assertEquals(raw.text, line.text)
        org.junit.Assert.assertNull(line.confidence)
    }

}
