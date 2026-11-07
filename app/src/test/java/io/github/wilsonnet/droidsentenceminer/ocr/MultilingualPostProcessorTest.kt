package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MultilingualPostProcessorTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrLine(text, BoundingBox(l, t, r, b))

    @Test
    fun latinRowsJoinWithSpacesAndKeepPunctuation() {
        val merged = OcrPostProcessor.merge(listOf(line("Hello,", 0, 0, 100, 40), line("world!", 120, 0, 220, 40)), profile = TextProfile.LATIN)
        assertEquals("Hello, world!", OcrPostProcessor.toSentenceText(merged, TextProfile.LATIN))
    }

    @Test
    fun japaneseDefaultStillMapsPunctuationAndGlues() {
        val merged = OcrPostProcessor.merge(listOf(line("今日は", 0, 0, 100, 40), line("晴れ!", 110, 0, 200, 40)))
        assertEquals("今日は晴れ！", OcrPostProcessor.toSentenceText(merged))
    }

    @Test
    fun latinDoesNotTurnDashesIntoChoonpu() {
        assertEquals("state-of-the-art – fine", OcrPostProcessor.normalize("state-of-the-art – fine", TextProfile.LATIN))
    }

    @Test
    fun noiseFilterUsesTheProfileScript() {
        val merged = OcrPostProcessor.merge(
            listOf(line("Она говорила", 0, 0, 300, 40), line("HP 100", 0, 200, 80, 220)),
            profile = TextProfile.CYRILLIC,
        )
        assertEquals(listOf("Она говорила"), merged.map { it.text })
    }

    @Test
    fun chineseGluesHanButSpacesLatinWords() {
        val merged = OcrPostProcessor.merge(listOf(line("我们学习", 0, 0, 100, 40), line("中文", 105, 0, 160, 40)), profile = TextProfile.CHINESE)
        assertEquals("我们学习中文", merged.single().text)
    }

    @Test
    fun subtitleFilterAnchorsOnTheProfileScript() {
        val rows = listOf(
            OcrPostProcessor.MergedLine("The quick brown fox", BoundingBox(0, 100, 600, 140)),
            OcrPostProcessor.MergedLine("menu", BoundingBox(0, 0, 60, 10)),
        )
        assertEquals(listOf("The quick brown fox"), SubtitleLineFilter.filter(rows, TextProfile.LATIN).map { it.text })
    }
}
