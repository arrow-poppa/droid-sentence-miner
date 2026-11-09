// Ported from Chimahon (GPL-3.0): chimahon/src/main/java/chimahon/dictionary/de/GermanTextPreprocessors.kt
// (arrow-poppa/chimahon, branch agent/byok-ai-v2.4.8-ocr-models @ 4a78d45).
// Adapted for Droid Sentence Miner: package, imports and result type only.
package io.github.wilsonnet.droidsentenceminer.dict.lang.de

object GermanTextPreprocessors {

    fun eszettVariants(text: String): List<String> = listOf(
        text,
        text.replace("ß", "ss").replace("ẞ", "SS"),
        text.replace("ss", "ß").replace("SS", "ẞ"),
    )

    fun decapitalize(text: String): List<String> {
        val lower = text.lowercase()
        return if (lower != text) listOf(text, lower) else listOf(text)
    }

    fun allVariants(text: String): List<String> {
        return decapitalize(text).flatMap { eszettVariants(it) }.distinct()
    }
}
