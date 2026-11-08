package io.github.wilsonnet.droidsentenceminer.dict

import io.github.wilsonnet.droidsentenceminer.dict.FakeLookupStore.Companion.dictionary
import io.github.wilsonnet.droidsentenceminer.dict.FakeLookupStore.Row
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class MultilingualLookupTest {

    private fun store(vararg rows: Row) = FakeLookupStore.single(*rows)

    private fun lookup(store: FakeLookupStore) = TermLookup(store)

    private fun TermLookup.details(word: String, language: String) = runBlocking { lookup(word, language = language) }

    private fun TermLookup.select(text: String, index: Int, language: String) =
        runBlocking { selectionAt(text, index, language) }

    private fun TermLookup.range(text: String, index: Int, language: String) =
        runBlocking { wordAt(text, index, language) }

    private fun IntRange.of(text: String) = text.substring(first, last + 1)

    // --- English morphology ---------------------------------------------------------------------

    @Test
    fun `running finds run and the surface stays running`() {
        val l = lookup(store(Row(1, "run", rules = "v")))
        val details = l.details("running", "en")
        assertEquals("running", details.surface)
        assertEquals(listOf("run"), details.terms.map { it.expression })
        assertTrue(details.deinflection.isNotEmpty())

        val text = "I am running fast"
        val selection = l.select(text, 7, "en")
        assertEquals("running", selection.surface)
        assertEquals(5, selection.start)
        assertEquals(12, selection.end)
        assertEquals(selection.surface, text.substring(selection.start, selection.end))
        assertTrue(selection.candidates.any { it.text == "run" })
        assertEquals("en-chimahon+fushi", selection.analyzer)
    }

    @Test
    fun `irregular forms find their lemma`() {
        val l = lookup(
            store(
                Row(1, "go", rules = "v"), Row(1, "take", rules = "v"), Row(1, "be", rules = "v"),
                Row(1, "child", rules = "n"), Row(1, "mouse", rules = "n"), Row(1, "goose", rules = "n"),
                Row(1, "good", rules = "adj"), Row(1, "well", rules = "adv"),
            ),
        )
        assertEquals("go", l.details("went", "en").terms.first().expression)
        assertEquals("take", l.details("took", "en").terms.first().expression)
        assertEquals("take", l.details("taken", "en").terms.first().expression)
        assertEquals("be", l.details("was", "en").terms.first().expression)
        assertEquals("child", l.details("children", "en").terms.first().expression)
        assertEquals("mouse", l.details("mice", "en").terms.first().expression)
        assertEquals("goose", l.details("geese", "en").terms.first().expression)
        assertEquals(setOf("good", "well"), l.details("better", "en").terms.map { it.expression }.toSet())
        assertEquals("child", l.details("children's", "en").terms.first().expression)
        // The tapped word, never the lemma.
        assertEquals("children", l.details("children", "en").surface)
    }

    @Test
    fun `phrasal verbs deinflect through their head`() {
        val l = lookup(store(Row(1, "give up", rules = "v"), Row(1, "pick up", rules = "v"), Row(1, "look away", rules = "v")))
        assertEquals("give up", l.details("gave up", "en").terms.first().expression)
        assertEquals("give up", l.details("given up", "en").terms.first().expression)
        assertEquals("pick up", l.details("picked it up", "en").terms.first().expression)
        assertEquals("look away", l.details("looked away", "en").terms.first().expression)
        assertEquals("give up", l.details("gave it up", "en").terms.first().expression)
    }

    @Test
    fun `a phrasal verb is selected only when the dictionary has it`() {
        val l = lookup(store(Row(1, "give up", rules = "v"), Row(1, "pick up", rules = "v"), Row(1, "up", rules = "adv")))
        val gave = "He gave up quickly"
        assertEquals("gave up", l.range(gave, 3, "en").of(gave))
        assertEquals("gave up", l.range(gave, 8, "en").of(gave)) // tapping `up` covers the same expression
        val picked = "She picked it up today"
        assertEquals("picked it up", l.range(picked, 5, "en").of(picked))
        // `He` has no verified expression: a lone word.
        assertEquals("He", l.range(gave, 0, "en").of(gave))
        // No `look forward to` entry: just the word.
        val look = "I look forward to it"
        assertEquals("forward", l.range(look, 8, "en").of(look))
        val withEntry = lookup(store(Row(1, "look forward to", rules = "v_phr")))
        assertEquals("look forward to", withEntry.range(look, 8, "en").of(look))
    }

    // --- Hyphenated compounds, word boundaries ---------------------------------------------------

    @Test
    fun `open-source selects and scans as one surface for every compound spelling`() {
        for (surface in listOf("open-source", "open‐source", "open‑source", "Open‑Source")) {
            for (headword in listOf("open-source", "open source", "opensource")) {
                val l = lookup(store(Row(1, "open"), Row(1, "source"), Row(1, headword)))
                val text = "🙂 an $surface tool"
                val start = text.indexOf(surface)
                val end = start + surface.length
                // Either half and the hyphen itself resolve to the same original surface.
                for (tap in listOf(start, start + 4, end - 1)) {
                    val selection = l.select(text, tap, "en")
                    assertEquals(surface, selection.surface)
                    assertEquals(start, selection.start)
                    assertEquals(end, selection.end)
                    assertEquals(text.substring(selection.start, selection.end), selection.surface)
                    assertEquals(headword, l.details(selection.surface, "en").terms.first().expression)
                }
                val scanned = runBlocking { l.scan(text, language = "en") }
                assertEquals(listOf(surface), scanned.map { it.text })
                assertEquals(start, scanned.single().start)
                assertEquals(end, scanned.single().end)
                assertEquals(headword, scanned.single().results.first().expression)
            }
        }
    }

    @Test
    fun `state-of-the-art covers all parts with spaced or joined dictionary spellings`() {
        val surface = "state-of-the-art"
        val text = "a $surface phone"
        for (headword in listOf(surface, "state of the art", "stateoftheart")) {
            val l = lookup(
                store(Row(1, "state"), Row(1, "of"), Row(1, "the"), Row(1, "art"), Row(1, headword)),
            )
            for (part in listOf("state", "of", "the", "art")) {
                assertEquals(surface, l.range(text, text.indexOf(part), "en").of(text))
            }
            val scanned = runBlocking { l.scan(text, language = "en") }
            assertEquals(listOf(surface), scanned.map { it.text })
            assertEquals(headword, scanned.single().results.first().expression)
        }
    }

    @Test
    fun `compound results rank original spelling before spaces before joined forms`() {
        val l = lookup(
            store(
                Row(1, "opensource", score = 100), Row(1, "open source", score = 50),
                Row(1, "open-source", score = -5), Row(1, "open‑source", score = -10),
            ),
        )
        assertEquals(
            listOf("open-source", "open source", "opensource"),
            l.details("open-source", "en").terms.map { it.expression },
        )
        val expected = listOf("open‑source", "open-source", "open source", "opensource")
        assertEquals(expected, l.details("open‑source", "en").terms.map { it.expression })
        val scanned = runBlocking { l.scan("open‑source", language = "en") }
        assertEquals(expected, scanned.single().results.map { it.expression })
    }

    @Test
    fun `unknown compounds fall back to the part under the finger and scan the parts`() {
        val l = lookup(store(Row(1, "open"), Row(1, "source")))
        for (surface in listOf("open-source", "open‐source", "open‑source")) {
            val text = "an $surface tool"
            for (part in listOf("open", "source")) {
                val start = text.indexOf(part)
                val selection = l.select(text, start + 1, "en")
                assertEquals(part, selection.surface)
                assertEquals(start, selection.start)
                assertEquals(start + part.length, selection.end)
            }
            val scanned = runBlocking { l.scan(text, language = "en") }
            assertEquals(listOf("open", "source"), scanned.map { it.text })
            assertEquals(listOf(text.indexOf("open"), text.indexOf("source")), scanned.map { it.start })
        }
        // A known phrase ending before the hyphen cannot cover a tap on the hyphen itself.
        val prefix = lookup(store(Row(1, "an open"), Row(1, "source")))
        assertEquals("-", prefix.range("an open-source", 7, "en").of("an open-source"))
    }

    @Test
    fun `punctuation dashes and edge hyphens do not select compounds`() {
        val l = lookup(store(Row(1, "word"), Row(1, "word-word"), Row(1, "word word"), Row(1, "wordword")))
        for (text in listOf("word — word", "word – word", "word - word", "word--word", "-word-")) {
            for ((i, c) in text.withIndex()) {
                if (c.isLetter()) {
                    assertEquals("word", l.range(text, i, "en").of(text))
                } else {
                    assertEquals(c.toString(), l.range(text, i, "en").of(text))
                }
            }
            val scanned = runBlocking { l.scan(text, language = "en") }
            assertEquals(if (text == "-word-") listOf("word") else listOf("word", "word"), scanned.map { it.text })
        }
    }

    @Test
    fun `hyphenated compounds need a dictionary match that covers the tap`() {
        val text = "the gaming-mode switch"
        val tapMode = text.indexOf("mode")
        val tapGaming = text.indexOf("gaming")
        val without = lookup(store(Row(1, "gaming"), Row(1, "mode")))
        assertEquals("mode", without.range(text, tapMode, "en").of(text)) // never `gaming`
        assertEquals("gaming", without.range(text, tapGaming, "en").of(text))
        val with = lookup(store(Row(1, "gaming"), Row(1, "mode"), Row(1, "gaming-mode")))
        assertEquals("gaming-mode", with.range(text, tapMode, "en").of(text))
        assertEquals("gaming-mode", with.range(text, tapGaming, "en").of(text))

        val long = lookup(store(Row(1, "state-of-the-art"), Row(1, "jack-of-all-trades")))
        val a = "a state-of-the-art phone"
        assertEquals("state-of-the-art", long.range(a, a.indexOf("of"), "en").of(a))
        val j = "he is a jack-of-all-trades"
        assertEquals("jack-of-all-trades", long.range(j, j.indexOf("all"), "en").of(j))
    }

    @Test
    fun `a tap inside a word never selects a fragment`() {
        val l = lookup(store(Row(1, "hello"), Row(1, "world")))
        val text = "hello wonderful world, café-au-lait"
        val words = listOf("hello", "wonderful", "world", "café", "au", "lait")
        for ((i, c) in text.withIndex()) {
            val range = l.range(text, i, "en")
            if (c.isLetter()) {
                val word = words.first { text.indexOf(it) <= i && i < text.indexOf(it) + it.length }
                assertEquals("index $i", word, range.of(text))
            } else {
                assertEquals("index $i", 1, range.last - range.first + 1)
            }
        }
        // Unknown words are still selected whole, and the engine never asked for a suffix.
        val s = store(Row(1, "hello"))
        val tl = lookup(s)
        assertEquals("world", tl.range("hello world", 8, "en").of("hello world"))
        assertTrue(s.queriedForms.none { it == "orld" || it == "rld" || it == "ello" })
    }

    @Test
    fun `accents are one word in NFC and NFD`() {
        val l = lookup(store(Row(1, "café")))
        val nfc = "un café noir"
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
        assertEquals("café", l.range(nfc, 5, "fr").of(nfc))
        for (i in 3 until 3 + "café".length + 1 - (if (nfd.length > nfc.length) 0 else 1)) {
            assertEquals("café", l.range(nfd, i, "fr").of(nfd))
        }
        assertEquals("café", l.details("café", "fr").terms.first().expression)
        assertEquals("café", l.details("café", "fr").surface)
    }

    // --- Apostrophes ------------------------------------------------------------------------------

    @Test
    fun `English contractions stay one token and find their base`() {
        val l = lookup(
            store(
                Row(1, "do", rules = "v"), Row(1, "it"), Row(1, "John", rules = "n"),
                Row(1, "we"), Row(1, "run", rules = "v"),
            ),
        )
        assertEquals("do", l.details("don't", "en").terms.first().expression)
        assertEquals("do", l.details("don’t", "en").terms.first().expression)
        assertEquals("it", l.details("it's", "en").terms.first().expression)
        assertEquals("John", l.details("John's", "en").terms.first().expression)
        assertEquals("we", l.details("we've", "en").terms.first().expression)
        val text = "I don’t know, it's fine"
        assertEquals("don’t", l.range(text, text.indexOf("t "), "en").of(text))
        assertEquals("it's", l.range(text, text.indexOf("it's") + 3, "en").of(text))
    }

    @Test
    fun `French and Italian elisions are selected whole and offer the noun`() {
        val fr = lookup(store(Row(1, "homme", rules = "n"), Row(1, "aimer", rules = "v")))
        val text = "Voilà l’homme qui"
        val selection = fr.select(text, text.indexOf('h'), "fr")
        assertEquals("l’homme", selection.surface)
        assertTrue(selection.candidates.any { it.text == "homme" && it.reasons == listOf("elision") })
        assertEquals("homme", fr.details("l’homme", "fr").terms.first().expression)
        assertEquals("homme", fr.details("l'homme", "fr").terms.first().expression)

        val it = lookup(store(Row(1, "arte", rules = "n")))
        assertEquals("arte", it.details("dell’arte", "it").terms.first().expression)
        val line = "il museo dell’arte antica"
        assertEquals("dell’arte", it.range(line, line.indexOf("arte") + 1, "it").of(line))
        // Quotes around a word are not elisions.
        val quoted = "say 'hello' now"
        assertEquals("hello", lookup(store(Row(1, "hello"))).range(quoted, 6, "en").of(quoted))
    }

    // --- Rules (part of speech) -------------------------------------------------------------------

    @Test
    fun `a dictionary without rules accepts deinflections, one with rules keeps filtering`() {
        val posLess = store(Row(1, "run"), Row(1, "walk"))
        assertEquals(listOf("run"), lookup(posLess).details("running", "en").terms.map { it.expression })
        assertTrue(posLess.hasRulesQueries >= 1)

        val withRules = store(Row(1, "run", rules = "n"), Row(1, "walk", rules = "v"))
        val l = lookup(withRules)
        assertTrue("a noun is not the lemma of a verb form", l.details("running", "en").terms.isEmpty())
        assertEquals(listOf("walk"), l.details("walking", "en").terms.map { it.expression })
        // Exact surface always matches, whatever its rules.
        assertEquals(listOf("run"), l.details("run", "en").terms.map { it.expression })

        // An entry without rules in a dictionary that has rules elsewhere does not inflect.
        val mixed = store(Row(1, "run"), Row(1, "walk", rules = "v"))
        assertTrue(lookup(mixed).details("running", "en").terms.isEmpty())
    }

    @Test
    fun `noun hierarchy covers singular and plural`() {
        val l = lookup(store(Row(1, "cat", rules = "ns"), Row(1, "dog", rules = "n")))
        assertEquals("cat", l.details("cats", "en").terms.first().expression)
        assertEquals("dog", l.details("dogs", "en").terms.first().expression)
    }

    // --- Ranking ----------------------------------------------------------------------------------

    @Test
    fun `an alias row with the lemma's glossary is dropped, a real entry stays`() {
        val same = "[\"to be part of\"]"
        val alias = store(Row(1, "belongs", gloss = same), Row(1, "belong", rules = "v", gloss = same))
        assertEquals(listOf("belong"), lookup(alias).details("belongs", "en").terms.map { it.expression })

        val distinct = store(
            Row(1, "belongs", gloss = "[\"possessions\"]"),
            Row(1, "belong", rules = "v", gloss = same),
        )
        assertEquals(listOf("belongs", "belong"), lookup(distinct).details("belongs", "en").terms.map { it.expression })
    }

    @Test
    fun `exact surface ranks before normalization before deinflection before irregular`() {
        val l = lookup(
            store(
                Row(1, "Mice", rules = "n"), Row(1, "mice", rules = "n"), Row(1, "mouse", rules = "n"),
            ),
        )
        assertEquals(listOf("Mice", "mice", "mouse"), l.details("Mice", "en").terms.map { it.expression })
    }

    // --- Other languages ----------------------------------------------------------------------------

    @Test
    fun `Chinese picks the longest dictionary match that covers the tap`() {
        val l = lookup(store(Row(1, "学习", reading = "xue2 xi2"), Row(1, "中文", reading = "zhong1 wen2")))
        val text = "学习中文"
        assertEquals("学习", l.range(text, 0, "zh").of(text))
        assertEquals("学习", l.range(text, 1, "zh").of(text))
        assertEquals("中文", l.range(text, 2, "zh").of(text))
        assertEquals("中文", l.range(text, 3, "zh").of(text))
        assertEquals("cjk-longest-match", l.select(text, 2, "zh-Hans").analyzer)

        val whole = lookup(store(Row(1, "学习"), Row(1, "中文"), Row(1, "学习中文")))
        assertEquals("学习中文", whole.range(text, 2, "zh").of(text))
        // Unknown text falls back to the character itself, never the whole line.
        assertEquals("字", lookup(store(Row(1, "学习"))).range("单字", 1, "zh").of("单字"))
        // The scan segments the line.
        val scanned = runBlocking { l.scan(text, language = "zh") }
        assertEquals(listOf("学习", "中文"), scanned.map { it.text })
        assertEquals(listOf(0, 2), scanned.map { it.start })
    }

    @Test
    fun `Russian keeps the surface and its offsets and normalizes stress marks`() {
        val l = lookup(store(Row(1, "говорила"), Row(1, "ёлка")))
        val text = "Она говори́ла тихо"
        val selection = l.select(text, 6, "ru")
        assertEquals("говори́ла", selection.surface)
        assertEquals(4, selection.start)
        assertEquals(text.substring(selection.start, selection.end), selection.surface)
        assertTrue(selection.candidates.any { it.text == "говорила" })
        assertEquals("говорила", l.details(selection.surface, "ru").terms.first().expression)
        assertEquals("ёлка", l.details("елка", "ru").terms.first().expression)
    }

    @Test
    fun `Arabic offsets refer to the original string`() {
        val l = lookup(store(Row(1, "كتاب", rules = "n")))
        val plain = "ذهب وبالكتاب هناك"
        val selection = l.select(plain, plain.indexOf("بال") + 1, "ar")
        assertEquals("وبالكتاب", selection.surface)
        assertEquals(plain.substring(selection.start, selection.end), selection.surface)
        assertTrue(selection.candidates.any { it.text == "كتاب" })
        assertEquals("كتاب", l.details("وبالكتاب", "ar").terms.first().expression)

        val voweled = "ذهب وَبِالْكِتَابِ هناك"
        val word = "وَبِالْكِتَابِ"
        val s2 = l.select(voweled, voweled.indexOf("الْ"), "ar")
        assertEquals(word, s2.surface)
        assertEquals(voweled.indexOf(word), s2.start)
        assertEquals(voweled.indexOf(word) + word.length, s2.end)
        assertTrue(s2.candidates.any { it.text == "وبالكتاب" })
        assertEquals("كتاب", l.details(word, "ar").terms.first().expression)
    }

    @Test
    fun `German and Korean deinflect and other languages select whole words`() {
        val de = lookup(store(Row(1, "machen", rules = "v"), Row(1, "Haus", rules = "n")))
        assertEquals("machen", de.details("gemacht", "de").terms.first().expression)
        assertEquals("Haus", de.details("haus", "de").terms.first().expression)
        val ko = lookup(store(Row(1, "먹다", rules = "v")))
        val text = "밥을 먹었다 어제"
        assertEquals("먹었다", ko.range(text, text.indexOf('었'), "ko").of(text))
        assertTrue(ko.select(text, text.indexOf('었'), "ko").candidates.any { it.text == "먹다" })
        assertEquals("먹다", ko.details("먹었다", "ko").terms.first().expression)
        // Generic fallback: whole word, case folding, no deinflection.
        val sv = lookup(store(Row(1, "hund")))
        val line = "Min Hundar är"
        assertEquals("Hundar", sv.range(line, 6, "sv").of(line))
        assertEquals("hund", sv.details("Hund", "sv").terms.first().expression)
        assertTrue(sv.details("hundar", "sv").terms.isEmpty())
        assertEquals("space-delimited", sv.select(line, 6, "sv").analyzer)
        // Portuguese: case folding.
        assertEquals("casa", lookup(store(Row(1, "casa"))).details("Casa", "pt").terms.first().expression)
    }

    @Test
    fun `scan finds expressions left to right in space-delimited text`() {
        val l = lookup(store(Row(1, "give up", rules = "v"), Row(1, "he"), Row(1, "quickly")))
        val text = "He gave up quickly"
        val scanned = runBlocking { l.scan(text, language = "en") }
        assertEquals(listOf("He", "gave up", "quickly"), scanned.map { it.text })
        assertEquals(listOf(0, 3, 11), scanned.map { it.start })
        assertEquals("give up", scanned[1].results.first().expression)
    }

    // --- Japanese is unchanged ---------------------------------------------------------------------

    @Test
    fun `Japanese stays on its own path by default`() {
        val l = lookup(store(Row(1, "食べる", rules = "v1", reading = "たべる")))
        val details = runBlocking { l.lookup("食べました") }
        assertEquals("食べました", details.surface)
        assertEquals("食べる", details.terms.first().expression)
        val scanned = runBlocking { l.scan("食べました") }
        assertEquals(listOf("食べました"), scanned.map { it.text })
        assertEquals(0 until 5, runBlocking { l.wordAt("食べました", 2) })
        val selection = l.select("食べました", 2, "ja")
        assertEquals("食べました", selection.surface)
        assertEquals("ja-yomitan", selection.analyzer)
        assertTrue(selection.candidates.any { it.text == "食べる" })
        // An unknown code (or blank) must not switch the Japanese path off by accident.
        assertTrue(runBlocking { l.lookup("食べました", language = "") }.terms.isNotEmpty())
    }

    @Test
    fun `rules queries are cached per dictionary size`() {
        val s = FakeLookupStore(
            listOf(dictionary(1, termCount = 10)),
            listOf(Row(1, "run"), Row(1, "walk")),
        )
        val l = lookup(s)
        l.details("running", "en")
        l.details("walking", "en")
        l.details("jumping", "en")
        assertEquals(1, s.hasRulesQueries)
    }
    @Test
    fun `dictionary invalidation also clears POS availability for a same-size replacement`() {
        val s = store(Row(1, "run"))
        val l = lookup(s)
        assertEquals("run", l.details("running", "en").baseForm)
        assertEquals(1, s.hasRulesQueries)
        l.invalidateCaches()
        assertEquals("run", l.details("running", "en").baseForm)
        assertEquals(2, s.hasRulesQueries)
    }

    @Test
    fun `empty and out-of-range taps return valid original offsets`() {
        val l = lookup(store())
        assertEquals(0 until 0, runBlocking { l.wordAt("", 0) })
        assertEquals(0 until 1, runBlocking { l.wordAt("猫", 100) })
        assertEquals("world", l.select("world", 100, "en").surface)
    }

    @Test
    fun `unknown emoji fallback retains the whole grapheme`() {
        val text = "a 👩‍💻 b"
        val l = lookup(store())
        assertEquals("👩‍💻", l.select(text, text.indexOf("👩"), "en").surface)
    }

}
