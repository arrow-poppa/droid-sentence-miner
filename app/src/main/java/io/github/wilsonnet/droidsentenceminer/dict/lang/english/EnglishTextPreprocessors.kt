// Ported from Chimahon (GPL-3.0): chimahon/src/main/java/chimahon/dictionary/english/EnglishTextPreprocessors.kt
// (arrow-poppa/chimahon, branch agent/byok-ai-v2.4.8-ocr-models @ 4a78d45).
// Adapted for Droid Sentence Miner: package, imports and result type only.
package io.github.wilsonnet.droidsentenceminer.dict.lang.english

object EnglishTextPreprocessors {

    fun decapitalize(text: String): List<String> {
        val lower = text.lowercase()
        return if (lower != text) listOf(text, lower) else listOf(text)
    }
}
