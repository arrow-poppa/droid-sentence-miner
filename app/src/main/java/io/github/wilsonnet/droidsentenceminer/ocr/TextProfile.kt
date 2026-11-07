package io.github.wilsonnet.droidsentenceminer.ocr

import java.lang.Character.UnicodeScript

/**
 * What counts as "the text being read" for a study language: drives OCR noise filtering, row
 * joining, the dialogue-row filter, two-pass scoring and which lines get a tappable screen layer.
 *
 * [JAPANESE] reproduces the pipeline's original rules exactly ([JapaneseTextScore]); every other
 * profile is script-based and never uses Japanese heuristics (furigana, full-width punctuation).
 * Pure Kotlin, unit tested.
 */
class TextProfile private constructor(
    val id: String,
    private val scripts: Set<UnicodeScript>,
    /** Words are separated by spaces: OCR rows are joined with a space, never glued. */
    val spaceDelimited: Boolean,
) {
    val isJapanese: Boolean get() = this === JAPANESE

    /** Written right to left: evenly split glyph boxes run from the right edge. */
    val rightToLeft: Boolean get() = this === ARABIC || this === HEBREW

    /** Can be set in vertical columns (tategaki); other scripts are always read as horizontal lines. */
    val allowsVertical: Boolean get() = this === JAPANESE || this === CHINESE

    /** A letter of this profile's writing system. */
    fun isTarget(ch: Char): Boolean {
        if (isJapanese) return JapaneseTextScore.isJapanese(ch)
        if (!ch.isLetter() && Character.getType(ch) != Character.NON_SPACING_MARK.toInt() &&
            Character.getType(ch) != Character.COMBINING_SPACING_MARK.toInt()
        ) {
            return false
        }
        val script = UnicodeScript.of(ch.code)
        return script in scripts || (script == UnicodeScript.INHERITED && ch.code in 0x0300..0x036F && UnicodeScript.LATIN in scripts)
    }

    fun count(text: String): Int = text.count(::isTarget)

    /** Higher reads better; the two-pass refinement keeps the higher-scoring read. */
    fun score(text: String): Double {
        if (isJapanese) return JapaneseTextScore.score(text)
        if (text.isBlank()) return 0.0
        var target = 0
        var letters = 0
        var good = 0
        var bad = 0
        for (ch in text) {
            when {
                ch == '�' -> bad += 4
                ch.isWhitespace() -> Unit
                ch.isLetterOrDigit() -> {
                    good++
                    letters++
                    if (isTarget(ch)) target++
                }
                else -> good++
            }
        }
        if (letters == 0) return 0.0
        val penalty = bad.toDouble() / (good + bad).coerceAtLeast(1)
        return target.toDouble() / letters * 10.0 + good * 0.01 - penalty * 20.0
    }

    /** Auto OCR's "is this a line of text in the study language" gate. */
    fun looksLikeText(text: String): Boolean =
        if (isJapanese) count(text) >= 2 && score(text) >= 3.0 else count(text) >= 1 && score(text) >= 5.0

    /** Share of letters/digits that belong to this profile (0..1). */
    fun ratio(text: String): Float {
        var significant = 0
        var target = 0
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                significant++
                if (isTarget(ch)) target++
            }
        }
        return if (significant == 0) 0f else target.toFloat() / significant
    }

    override fun toString(): String = "TextProfile($id)"

    companion object {
        val JAPANESE = TextProfile("ja", setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA), spaceDelimited = false)
        val CHINESE = TextProfile("zh", setOf(UnicodeScript.HAN, UnicodeScript.BOPOMOFO), spaceDelimited = false)
        val KOREAN = TextProfile("ko", setOf(UnicodeScript.HANGUL, UnicodeScript.HAN), spaceDelimited = true)
        val LATIN = TextProfile("latin", setOf(UnicodeScript.LATIN), spaceDelimited = true)
        val CYRILLIC = TextProfile("cyrillic", setOf(UnicodeScript.CYRILLIC), spaceDelimited = true)
        val GREEK = TextProfile("greek", setOf(UnicodeScript.GREEK), spaceDelimited = true)
        val ARABIC = TextProfile("arabic", setOf(UnicodeScript.ARABIC), spaceDelimited = true)
        val DEVANAGARI = TextProfile("devanagari", setOf(UnicodeScript.DEVANAGARI), spaceDelimited = true)
        val THAI = TextProfile("thai", setOf(UnicodeScript.THAI), spaceDelimited = false)
        val HEBREW = TextProfile("hebrew", setOf(UnicodeScript.HEBREW), spaceDelimited = true)

        private val CYRILLIC_LANGS = setOf("ru", "uk", "be", "bg", "mk", "sr", "kk", "ky", "mn", "tg", "tt", "ba", "cv", "os", "ce", "sah", "ab", "kbd", "ady", "av", "dar", "inh", "lbe", "lez", "tab")
        private val ARABIC_LANGS = setOf("ar", "fa", "ur", "ps", "ug", "sd", "ckb", "ku")
        private val DEVANAGARI_LANGS = setOf("hi", "mr", "ne", "sa", "bh", "mai", "bho", "new", "kok", "gom")

        /** The profile for a BCP-47 study-language code (base subtag decides; unknown → Latin). */
        fun forLanguage(code: String): TextProfile {
            val subtags = code.lowercase().replace('_', '-').split('-')
            val base = subtags.first()
            val explicitScript = subtags.drop(1).firstOrNull { it.length == 4 }
            when (explicitScript) {
                "latn" -> return LATIN
                "cyrl" -> return CYRILLIC
                "arab" -> return ARABIC
                "deva" -> return DEVANAGARI
                "hebr" -> return HEBREW
            }
            return when {
                base == "ja" -> JAPANESE
                base == "zh" || base == "yue" || base == "lzh" -> CHINESE
                base == "ko" -> KOREAN
                base == "el" -> GREEK
                base == "th" -> THAI
                base == "he" || base == "yi" -> HEBREW
                base in CYRILLIC_LANGS -> CYRILLIC
                base in ARABIC_LANGS -> ARABIC
                base in DEVANAGARI_LANGS -> DEVANAGARI
                else -> LATIN
            }
        }
    }
}
