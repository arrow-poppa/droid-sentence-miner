/*
 * Port of ext/js/language/ai-fallback-util.js (Yomitan BYOK fork, GPL-3.0-or-later,
 * Copyright (C) 2023-2026 Yomitan Authors).
 */
package io.github.wilsonnet.droidsentenceminer.ai

/**
 * Offering an AI explanation for a word no local dictionary knows. It only decides *what* to
 * explain; it never fabricates a dictionary entry, reading or glossary.
 *
 * Languages without word delimiters (ja, zh, th, …) must not use the leading-word regex: `\p{L}`
 * covers kanji, kana and Thai, so it would swallow a whole clause. For them only the surface the
 * user actually selected/tapped is used ([target]).
 */
object AiFallback {
    /** Name of the virtual "dictionary" a UI may show for an AI-only result (it is not stored anywhere). */
    const val DICTIONARY_NAME = "AI Fallback"

    val NO_WORD_BOUNDARY_LANGUAGES: Set<String> = setOf("ja", "zh", "yue", "th", "lo", "km", "my", "bo")

    private val WORD_TOKEN = Regex("^[\\p{L}\\p{M}\\p{N}'’-]+")
    private val EDGE_START = Regex("^['’-]+")
    private val EDGE_END = Regex("['’-]+$")
    private val LETTER = Regex("\\p{L}")

    data class Token(val term: String, val textLength: Int)

    /** The base subtag of [language] (`zh-Hant` → `zh`) is what decides it, so script/region variants behave alike. */
    fun isLanguageSupported(language: String?): Boolean {
        val base = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()
        return base.isNotEmpty() && base !in NO_WORD_BOUNDARY_LANGUAGES
    }

    /**
     * The word that starts [text] (word characters plus inner `'` and `-`), or null. `textLength`
     * can exceed `term.length` when the text starts with a separator that is stripped from the word.
     * Length-preserving normalisation, so the length applies to the original text.
     */
    fun token(text: String): Token? {
        val match = WORD_TOKEN.find(text.replace('’', '\'')) ?: return null
        val scanned = match.value.replace(EDGE_END, "")
        val term = scanned.replace(EDGE_START, "")
        if (term.isEmpty() || !LETTER.containsMatchIn(term)) return null
        return Token(term, scanned.length)
    }

    /**
     * What to explain when the local lookup found nothing.
     *
     * @param selectedSurface the text the user selected/tapped, preferred whenever present (any language)
     * @param scanText text starting at the tap point, used only for delimiting languages when nothing was selected
     */
    fun target(language: String?, selectedSurface: String?, scanText: String? = null): String? {
        selectedSurface?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (scanText == null || !isLanguageSupported(language)) return null
        return token(scanText)?.term
    }

    /** Whether to show an AI offer: the setting is on and no local entry exists. */
    fun shouldOffer(fallbackEnabled: Boolean, hasLocalEntry: Boolean): Boolean = fallbackEnabled && !hasLocalEntry
}
