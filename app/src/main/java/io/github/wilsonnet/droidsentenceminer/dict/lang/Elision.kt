// Clitic/elision tables follow Chimahon (GPL-3.0): fr/FrenchTextPreprocessors.kt (l d j m t s n c qu)
// and it/ItalianTextPreprocessors.kt (l dell all dall nell sull coll un quest quell c n)
// (arrow-poppa/chimahon @ 4a78d45), anchored at the start of the word (Chimahon's Italian regex
// also matched mid-word) and extended with the other common elided function words.
package io.github.wilsonnet.droidsentenceminer.dict.lang

import java.text.Normalizer

internal object Elision {
    private val french = setOf(
        "l", "d", "j", "m", "t", "s", "n", "c", "qu",
        "jusqu", "lorsqu", "puisqu", "quoiqu", "presqu",
    )
    private val italian = setOf(
        "l", "dell", "all", "dall", "nell", "sull", "coll", "un", "quest", "quell", "c", "n",
        "d", "m", "t", "s", "v", "gl", "anch", "com", "dov", "cos", "nessun", "sant", "bell", "buon",
    )

    private fun clitics(code: String): Set<String> = when (code) {
        "fr" -> french
        "it" -> italian
        else -> emptySet()
    }

    /**
     * `l'homme` → `homme`, `dell’arte` → `arte` (the clitic before the first apostrophe is one of
     * [code]'s, the rest is not empty). Null when [text] has no elision. Accepts ' and ’.
     */
    fun strip(code: String, text: String): String? {
        val table = clitics(code)
        if (table.isEmpty()) return null
        val at = text.indexOfFirst { it == '\'' || it == '’' || it == 'ʼ' }
        if (at <= 0 || at >= text.length - 1) return null
        if (text.substring(0, at).lowercase() !in table) return null
        return text.substring(at + 1)
    }

    /** Accents removed (`perché` → `perche`): dictionaries and OCR often drop them. Null if nothing changed. */
    fun foldDiacritics(text: String): String? {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return Normalizer.normalize(folded, Normalizer.Form.NFC).takeIf { it != text }
    }
}
