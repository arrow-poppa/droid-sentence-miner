package io.github.wilsonnet.droidsentenceminer.dict

import io.github.wilsonnet.droidsentenceminer.dict.lang.ConditionHierarchy
import io.github.wilsonnet.droidsentenceminer.dict.lang.LanguageParsers
import io.github.wilsonnet.droidsentenceminer.dict.lang.SegmentStrategy
import io.github.wilsonnet.droidsentenceminer.dict.lang.WordSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSpansTest {
    @Test
    fun `words are runs of letters marks and digits`() {
        val text = "Hello, wörld3 café!"
        val tokens = WordSpans.tokenize(text, joinApostrophes = false)
        assertEquals(listOf("Hello", "wörld3", "café"), tokens.map { text.substring(it.start, it.end) })
        assertEquals(1, WordSpans.tokenIndexAt(tokens, 9))
        assertEquals(-1, WordSpans.tokenIndexAt(tokens, 5))
    }

    @Test
    fun `apostrophes join only between letters and only where the language asks`() {
        val text = "'tis don't dogs' l’homme"
        assertEquals(
            listOf("tis", "don't", "dogs", "l’homme"),
            WordSpans.tokenize(text, true).map { text.substring(it.start, it.end) },
        )
        assertEquals(
            listOf("tis", "don", "t", "dogs", "l", "homme"),
            WordSpans.tokenize(text, false).map { text.substring(it.start, it.end) },
        )
    }

    @Test
    fun `spans are bounded and break at punctuation`() {
        val text = "one two three four five six seven, eight nine"
        val tokens = WordSpans.tokenize(text, false)
        val k = 3 // four
        val spans = WordSpans.spansContaining(text, tokens, k)
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { it.words in 2..WordSpans.MAX_WORDS && it.units <= WordSpans.MAX_UNITS })
        assertTrue(spans.all { it.firstToken <= k && k <= it.lastToken })
        assertTrue(spans.all { !it.probe.contains(',') })
        // The comma ends the run: nothing reaches `eight`.
        assertTrue(spans.none { it.probe.contains("eight") })
        assertTrue(spans.any { it.probe == "two three four five six" })
    }

    @Test
    fun `hyphens and spaces are normalized in the probe`() {
        val text = "a  state‐of-the art"
        val tokens = WordSpans.tokenize(text, false)
        val spans = WordSpans.spansContaining(text, tokens, 2)
        assertTrue(spans.any { it.probe == "state-of-the art" })
        assertTrue(spans.any { it.probe == "a state-of-the art" })
    }

    @Test
    fun `condition hierarchy relates noun and verb kinds`() {
        assertTrue(ConditionHierarchy.intersects(setOf("ns"), setOf("n")))
        assertTrue(ConditionHierarchy.intersects(setOf("n"), setOf("np")))
        assertTrue(ConditionHierarchy.intersects(setOf("v_phr"), setOf("v")))
        assertFalse(ConditionHierarchy.intersects(setOf("np"), setOf("ns")))
        assertFalse(ConditionHierarchy.intersects(setOf("v_phr"), setOf("n")))
        assertFalse(ConditionHierarchy.intersects(emptySet(), setOf("n")))
    }

    @Test
    fun `language codes are normalized and dispatched`() {
        assertEquals("zh", LanguageParsers.normalizeCode("zh-Hant_TW"))
        assertEquals("ja", LanguageParsers.normalizeCode(" "))
        assertEquals(SegmentStrategy.JAPANESE, LanguageParsers.strategy("ja"))
        assertEquals(SegmentStrategy.CJK_LONGEST_MATCH, LanguageParsers.strategy("zh-CN"))
        assertEquals(SegmentStrategy.CJK_LONGEST_MATCH, LanguageParsers.strategy("th"))
        assertEquals(SegmentStrategy.SPACE_DELIMITED, LanguageParsers.strategy("pt-BR"))
        assertEquals(SegmentStrategy.SPACE_DELIMITED, LanguageParsers.strategy("xx"))
        // Exact surface first, normalizations next, deinflections last.
        val candidates = LanguageParsers.forLanguage("en").candidates("Went")
        assertEquals("Went", candidates.first().text)
        assertEquals("went", candidates[1].text)
        assertTrue(candidates.indexOfFirst { it.text == "go" } > 1)
    }
}
