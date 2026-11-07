// Word boundaries for space-delimited text. The tap rule follows Fushi (GPL-3.0):
//   fushi/lib/src/lookup/latin_word_lookup.dart (tap -> whole word, hyphenated compound probing) and
//   native/fushidicts/fushidicts_src/scan/word_scan.cpp (never cut between two letters)
// (arrow-poppa/Fushi @ ac11600).
package io.github.wilsonnet.droidsentenceminer.dict.lang

/** A word: UTF-16 range `[start, end)` of the text. */
internal data class Token(val start: Int, val end: Int)

/** A run of consecutive words probed as one expression; [probe] is the text to look up (spaces collapsed). */
internal data class Span(val firstToken: Int, val lastToken: Int, val start: Int, val end: Int, val probe: String) {
    val words: Int get() = lastToken - firstToken + 1
    val units: Int get() = end - start
}

internal object WordSpans {
    /** Expressions are probed over at most this many words / UTF-16 units. */
    const val MAX_WORDS = 5
    const val MAX_UNITS = 48
    private const val NONE = '\u0000'

    private val scriptsWithoutSpaces = setOf(
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
    )

    fun isApostrophe(c: Char): Boolean = c == '\'' || c == '’' || c == 'ʼ'

    fun isHyphen(c: Char): Boolean = c == '-' || c == '‐' || c == '‑'

    private fun isWordCodePoint(cp: Int): Boolean {
        val type = Character.getType(cp)
        val mark = type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        if (!mark && !Character.isLetterOrDigit(cp)) return false
        // CJK/Thai text has no spaces: those characters are never "a word" here (CJK path handles them).
        return mark || Character.UnicodeScript.of(cp) !in scriptsWithoutSpaces
    }

    /** Whether the UTF-16 unit at [index] is a letter, combining mark or digit (surrogate pairs are checked whole). */
    fun isWordUnit(text: String, index: Int): Boolean {
        val c = text[index]
        return when {
            Character.isHighSurrogate(c) ->
                index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) &&
                    isWordCodePoint(text.codePointAt(index))
            Character.isLowSurrogate(c) ->
                index > 0 && Character.isHighSurrogate(text[index - 1]) &&
                    isWordCodePoint(text.codePointAt(index - 1))
            else -> isWordCodePoint(c.code)
        }
    }

    /**
     * Words of [text]: maximal runs of word units. With [joinApostrophes] an apostrophe between two
     * word units belongs to the word (`don't`, `John's`, `l'homme`, `dell’arte`); without it the
     * apostrophe ends the word. Hyphens always end the word (compounds are probed as spans).
     */
    fun tokenize(text: String, joinApostrophes: Boolean): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            if (!isWordUnit(text, i)) {
                i++
                continue
            }
            val start = i
            i++
            while (i < text.length) {
                if (isWordUnit(text, i)) {
                    i++
                } else if (joinApostrophes && isApostrophe(text[i]) && i + 1 < text.length && isWordUnit(text, i + 1)) {
                    i += 2
                } else {
                    break
                }
            }
            tokens += Token(start, i)
        }
        return tokens
    }

    /** Index of the token containing UTF-16 [index], or -1. */
    fun tokenIndexAt(tokens: List<Token>, index: Int): Int {
        var lo = 0
        var hi = tokens.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = tokens[mid]
            when {
                index < t.start -> hi = mid - 1
                index >= t.end -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /** Separator between consecutive tokens: ' ' (spaces/newlines only), '-' (one hyphen), or 0 (anything else breaks). */
    private fun separator(text: String, a: Token, b: Token): Char {
        if (b.start <= a.end) return NONE
        if (b.start - a.end == 1 && isHyphen(text[a.end])) return '-'
        for (i in a.end until b.start) if (!text[i].isWhitespace()) return NONE
        return ' '
    }

    private fun joinable(text: String, tokens: List<Token>, i: Int): Boolean =
        i in 0 until tokens.size - 1 && separator(text, tokens[i], tokens[i + 1]) != NONE

    private fun probe(text: String, tokens: List<Token>, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (t in from..to) {
            if (t > from) sb.append(separator(text, tokens[t - 1], tokens[t]))
            sb.append(text, tokens[t].start, tokens[t].end)
        }
        return sb.toString()
    }

    private fun spanOf(text: String, tokens: List<Token>, from: Int, to: Int): Span? {
        val start = tokens[from].start
        val end = tokens[to].end
        if (end - start > MAX_UNITS) return null
        return Span(from, to, start, end, probe(text, tokens, from, to))
    }

    /** Multi-word spans (≥ 2 words, joined by spaces or single hyphens) that contain token [k]. */
    fun spansContaining(text: String, tokens: List<Token>, k: Int): List<Span> {
        if (k !in tokens.indices) return emptyList()
        var left = k
        while (k - left < MAX_WORDS - 1 && joinable(text, tokens, left - 1)) left--
        var right = k
        while (right - left < MAX_WORDS - 1 && joinable(text, tokens, right)) right++
        val spans = ArrayList<Span>()
        for (from in left..k) {
            for (to in maxOf(k, from + 1)..minOf(right, from + MAX_WORDS - 1)) {
                spanOf(text, tokens, from, to)?.let { spans += it }
            }
        }
        return spans
    }

    /** Multi-word spans that start at token [k] (the scan path: every span of a position). */
    fun spansStartingAt(text: String, tokens: List<Token>, k: Int): List<Span> {
        if (k !in tokens.indices) return emptyList()
        val spans = ArrayList<Span>()
        var to = k
        while (to - k < MAX_WORDS - 1 && joinable(text, tokens, to)) {
            to++
            spanOf(text, tokens, k, to)?.let { spans += it } ?: break
        }
        return spans
    }
}
