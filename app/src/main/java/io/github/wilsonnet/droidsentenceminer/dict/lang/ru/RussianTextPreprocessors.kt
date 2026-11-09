// Ported from Chimahon (GPL-3.0): chimahon/src/main/java/chimahon/dictionary/ru/RussianTextPreprocessors.kt
// (arrow-poppa/chimahon, branch agent/byok-ai-v2.4.8-ocr-models @ 4a78d45).
// Adapted for Droid Sentence Miner: package, imports and result type only.
package io.github.wilsonnet.droidsentenceminer.dict.lang.ru

object RussianTextPreprocessors {

    fun yoToE(text: String): List<String> = listOf(
        text,
        text.replace('ё', 'е').replace('Ё', 'Е'),
        text.replace('е', 'ё').replace('Е', 'Ё'),
    )

    fun removeRussianDiacritics(text: String): List<String> = listOf(
        text,
        text.replace("\u0301", ""),
    )

    fun allVariants(text: String): List<String> {
        return removeRussianDiacritics(text).flatMap { yoToE(it) }.distinct()
    }
}
