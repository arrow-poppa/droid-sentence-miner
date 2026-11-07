package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProfileTest {

    @Test
    fun languagesMapToScripts() {
        assertSame(TextProfile.JAPANESE, TextProfile.forLanguage("ja"))
        assertSame(TextProfile.CHINESE, TextProfile.forLanguage("zh-Hant"))
        assertSame(TextProfile.KOREAN, TextProfile.forLanguage("ko"))
        assertSame(TextProfile.LATIN, TextProfile.forLanguage("pt-BR"))
        assertSame(TextProfile.CYRILLIC, TextProfile.forLanguage("ru"))
        assertSame(TextProfile.CYRILLIC, TextProfile.forLanguage("sr-Cyrl"))
        assertSame(TextProfile.LATIN, TextProfile.forLanguage("sr-Latn"))
        assertSame(TextProfile.ARABIC, TextProfile.forLanguage("ar"))
        assertSame(TextProfile.DEVANAGARI, TextProfile.forLanguage("hi"))
        assertSame(TextProfile.GREEK, TextProfile.forLanguage("el"))
        assertSame(TextProfile.LATIN, TextProfile.forLanguage("xx-unknown"))
    }

    @Test
    fun japaneseProfileIsTheOriginalScore() {
        for (text in listOf("今日は学校へ", "Hello", "「百花……っ」", "")) {
            assertEquals(JapaneseTextScore.score(text), TextProfile.JAPANESE.score(text), 0.0)
        }
        assertTrue(TextProfile.JAPANESE.looksLikeText("行こう"))
        assertFalse(TextProfile.JAPANESE.looksLikeText("Hello world"))
    }

    @Test
    fun latinCountsAccentsAndCombiningMarks() {
        assertEquals(4, TextProfile.LATIN.count("café"))
        assertEquals(5, TextProfile.LATIN.count("café")) // NFD é = e + U+0301
        assertTrue(TextProfile.LATIN.looksLikeText("Não sei"))
        assertFalse(TextProfile.LATIN.looksLikeText("12:30"))
    }

    @Test
    fun devanagariVowelSignsCount() {
        // हिन्दी: ह ि न ् द ी — letters plus combining vowel signs/virama
        assertEquals("हिन्दी".length, TextProfile.DEVANAGARI.count("हिन्दी"))
    }

    @Test
    fun scriptsDoNotLeak() {
        assertEquals(0, TextProfile.CYRILLIC.count("Hello"))
        assertEquals(0, TextProfile.LATIN.count("Привет"))
        assertEquals(1f, TextProfile.KOREAN.ratio("한국어"), 0f)
    }
}
