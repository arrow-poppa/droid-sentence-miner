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
    fun `probes collapse spaces but preserve the original hyphens`() {
        val text = "a  state‐of-the art"
        val tokens = WordSpans.tokenize(text, false)
        val spans = WordSpans.spansContaining(text, tokens, 2)
        assertTrue(spans.any { it.probe == "state‐of-the art" })
        assertTrue(spans.any { it.probe == "a state‐of-the art" })
    }

    @Test
    fun `compound spans cover either half and retain source offsets`() {
        for (compound in listOf("open-source", "open‐source", "open‑source", "state-of-the-art")) {
            val text = "🙂 $compound!"
            val start = text.indexOf(compound)
            val end = start + compound.length
            val tokens = WordSpans.tokenize(text, true)
            for (tap in listOf(start, end - 1)) {
                val k = WordSpans.tokenIndexAt(tokens, tap)
                val span = WordSpans.spansContaining(text, tokens, k).first { it.probe == compound }
                assertEquals(start, span.start)
                assertEquals(end, span.end)
                assertEquals(compound, text.substring(span.start, span.end))
            }
            assertTrue(WordSpans.spansStartingAt(text, tokens, 0).any { it.probe == compound })
        }
    }

    @Test
    fun `spaced hyphens and punctuation dashes never join spans`() {
        for (text in listOf("word — word", "word – word", "word - word", "word--word", "-word-")) {
            val tokens = WordSpans.tokenize(text, false)
            assertTrue(text, WordSpans.spansStartingAt(text, tokens, 0).isEmpty())
            assertTrue(text, WordSpans.spansContaining(text, tokens, tokens.lastIndex).isEmpty())
        }
    }

    @Test
    fun `English compound candidates prefer hyphens then spaces then joined spelling`() {
        val parser = LanguageParsers.forLanguage("en")
        for (surface in listOf("open-source", "state-of-the-art")) {
            val expected = listOf(surface, surface.replace('-', ' '), surface.replace("-", ""))
            assertEquals(expected, parser.candidates(surface).filter { it.reasons.isEmpty() }.map { it.text })
            assertEquals(expected, parser.expressionCandidates(surface).filter { it.reasons.isEmpty() }.map { it.text })
        }
        val variants = parser.candidates("Open‑Source").filter { it.reasons.isEmpty() }.map { it.text }
        assertEquals("Open‑Source", variants.first())
        assertTrue(variants.indexOf("open-source") < variants.indexOf("open source"))
        assertTrue(variants.indexOf("open source") < variants.indexOf("opensource"))
        for (surface in listOf("-word", "word-", "word — word", "word – word", "word - word", "word--word")) {
            assertEquals(listOf(surface), parser.candidates(surface).filter { it.reasons.isEmpty() }.map { it.text })
        }
        // Spelling fallbacks are English-specific.
        assertFalse(LanguageParsers.forLanguage("pt").candidates("open-source").any { it.text == "open source" })
    }

    @Test
    fun `condition hierarchy relates noun and verb kinds`() {
        assertTrue(ConditionHierarchy.intersects(setOf("ns"), setOf("n")))
        assertTrue(ConditionHierarchy.intersects(setOf("n"), setOf("np")))
        assertTrue(ConditionHierarchy.intersects(setOf("v_phr"), setOf("v")))
        assertTrue(ConditionHierarchy.intersects(setOf("vw"), setOf("v")))
        assertTrue(ConditionHierarchy.intersects(setOf("v"), setOf("vs")))
        assertFalse(ConditionHierarchy.intersects(setOf("vw"), setOf("vs")))
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
    @Test
    fun `span window includes expressions to the right of a tap with a long prefix`() {
        val text = "one two three four five six seven eight nine"
        val tokens = WordSpans.tokenize(text, false)
        val spans = WordSpans.spansContaining(text, tokens, 4)
        assertTrue(spans.any { it.probe == "five six seven eight nine" })
        assertTrue(spans.any { it.probe == "one two three four five" })
    }

}
