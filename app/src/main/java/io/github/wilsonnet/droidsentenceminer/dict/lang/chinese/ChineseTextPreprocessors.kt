// Ported from Chimahon (GPL-3.0): chimahon/src/main/java/chimahon/dictionary/chinese/ChineseTextPreprocessors.kt
// (arrow-poppa/chimahon, branch agent/byok-ai-v2.4.8-ocr-models @ 4a78d45).
// Adapted for Droid Sentence Miner: package, imports and result type only.
package io.github.wilsonnet.droidsentenceminer.dict.lang.chinese

object ChineseTextPreprocessors {
    fun process(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        
        // Yomitan's Chinese normalization: NFC, lowercase, remove whitespace and specific separators
        val normalized = trimmed.lowercase()
            .replace(Regex("[\\s・:'’\\-]"), "")
            .replace("//", "")
            
        return if (normalized != trimmed) listOf(trimmed, normalized) else listOf(trimmed)
    }
}
