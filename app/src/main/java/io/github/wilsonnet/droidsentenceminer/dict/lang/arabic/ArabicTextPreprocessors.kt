// Ported from Chimahon (GPL-3.0): chimahon/src/main/java/chimahon/dictionary/arabic/ArabicTextPreprocessors.kt
// (arrow-poppa/chimahon, branch agent/byok-ai-v2.4.8-ocr-models @ 4a78d45).
// Adapted for Droid Sentence Miner: package, imports and result type only.
package io.github.wilsonnet.droidsentenceminer.dict.lang.arabic

object ArabicTextPreprocessors {

    private val diacriticsRegex = Regex(
        "[\u0618\u0619\u061A\u064B\u064C\u064D\u064E\u064F\u0650\u0651\u0652\u0653\u0654\u0655\u0656\u0670]",
    )

    fun removeDiacritics(text: String): List<String> =
        listOf(text, text.replace(diacriticsRegex, ""))

    fun removeTatweel(text: String): List<String> =
        listOf(text, text.replace("ـ", ""))

    fun normalizeUnicode(text: String): List<String> =
        listOf(text, java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC))

    fun addHamzaTop(text: String): List<String> =
        listOf(text, text.replaceFirst("ا", "أ"))

    fun addHamzaBottom(text: String): List<String> =
        listOf(text, text.replaceFirst("ا", "إ"))

    fun convertAlifMaqsuraToYaa(text: String): List<String> =
        listOf(text, if (text.endsWith("ى")) text.dropLast(1) + "ي" else text)

    fun convertHaToTaMarbuta(text: String): List<String> =
        listOf(text, if (text.endsWith("ه")) text.dropLast(1) + "ة" else text)

    fun process(text: String): List<String> {
        var variants = linkedSetOf(text)
        val processors = listOf(
            ::removeDiacritics,
            ::removeTatweel,
            ::normalizeUnicode,
            ::addHamzaTop,
            ::addHamzaBottom,
            ::convertAlifMaqsuraToYaa,
            ::convertHaToTaMarbuta,
        )
        for (processor in processors) {
            val next = linkedSetOf<String>()
            for (variant in variants) {
                next.addAll(processor(variant))
            }
            variants = next
        }
        return variants.toList()
    }
}
