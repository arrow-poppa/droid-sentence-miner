package io.github.wilsonnet.droidsentenceminer.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.measureTimeMillis

/**
 * The five script recognizers of ML Kit Text Recognition v2. Each one reads every language of its
 * script ([MlKitLanguageCatalog]): there is no per-language model and no universal recognizer that
 * picks a script by itself. [bundled] models ship in the APK (work offline and without Google Play);
 * the others are downloaded by Google Play services on first use ([MlKitModules]).
 */
enum class MlKitScript(val id: String, val label: String, val bundled: Boolean) {
    LATIN("latin", "Latin", bundled = true),
    CHINESE("chinese", "Chinese", bundled = false),
    JAPANESE("japanese", "Japanese", bundled = true),
    KOREAN("korean", "Korean", bundled = false),
    DEVANAGARI("devanagari", "Devanagari", bundled = false),
    ;

    fun newClient(): TextRecognizer = TextRecognition.getClient(
        when (this) {
            LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        },
    )

    companion object {
        fun fromId(id: String?): MlKitScript? = entries.firstOrNull { it.id == id }

        /** The recognizer for a study language: the catalog's script model, Latin when it has none. */
        fun forLanguage(code: String): MlKitScript = MlKitLanguageCatalog.find(code)?.recognizer ?: when (TextProfile.forLanguage(code)) {
            TextProfile.JAPANESE -> JAPANESE
            TextProfile.CHINESE -> CHINESE
            TextProfile.KOREAN -> KOREAN
            TextProfile.DEVANAGARI -> DEVANAGARI
            else -> LATIN
        }
    }
}

/**
 * On-device OCR using one ML Kit script recognizer.
 *
 * Mirrors the two-pass strategy of GameSentenceMiner's OwOCR pipeline:
 *  1. Detect and recognize the whole capture region.
 *  2. Re-recognize each detected text block at higher resolution and keep
 *     whichever result reads better in the study language ([profile]).
 */
open class MlKitOcr(
    val script: MlKitScript,
    private val twoPass: Boolean = true,
    private val dropNoise: Boolean = true,
    val profile: TextProfile = TextProfile.JAPANESE,
    private val clientFactory: () -> TextRecognizer = { script.newClient() },
) : OcrEngine {

    override val name: String = "ML Kit ${script.label}"

    private val recognizer by lazy(clientFactory)

    /** The ML Kit client, for module availability checks (Google Play–delivered scripts). */
    val client: TextRecognizer get() = recognizer

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        var lines: List<OcrLine> = emptyList()
        val elapsed = measureTimeMillis {
            val prepared = ImagePreprocessor.upscaleForOcr(bitmap)
            try {
                val first = recognizeOnce(prepared)
                val merged = OcrPostProcessor.merge(first, dropNoise = dropNoise, profile = profile)
                val read = if (twoPass && merged.isNotEmpty()) refine(prepared, merged, first) else first
                lines = read.map { OcrGeometry.toSource(it, prepared.width, prepared.height, bitmap.width, bitmap.height) }
            } finally {
                if (prepared !== bitmap) prepared.recycle()
            }
        }
        val text = OcrPostProcessor.toSentenceText(OcrPostProcessor.merge(lines, dropNoise = dropNoise, profile = profile), profile)
        OcrResult(text = text, lines = lines, elapsedMs = elapsed)
    }

    private suspend fun recognizeOnce(bitmap: Bitmap): List<OcrLine> = recognizeBlocks(bitmap).flatten()

    /** Raw lines grouped by ML Kit's text blocks (paragraphs), no post-processing. */
    suspend fun recognizeBlocks(bitmap: Bitmap): List<List<OcrLine>> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text: Text = recognizer.process(image).awaitResult()
        return text.textBlocks.map { block -> linesOf(block) }.filter { it.isNotEmpty() }
    }

    private fun linesOf(block: Text.TextBlock): List<OcrLine> = block.lines.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        if (line.text.isBlank()) return@mapNotNull null
        OcrLine(
            text = line.text,
            box = BoundingBox.from(box),
            confidence = line.confidence,
            angle = line.angle,
            chars = if (profile.isJapanese) {
                // Unchanged for Japanese: book mode and the layer match these against the cleaned text.
                line.elements.flatMap { it.symbols }.mapNotNull { it.boundingBox?.let(BoundingBox::from) }
            } else {
                alignedChars(line, BoundingBox.from(box))
            },
        )
    }

    /** One box per character of the line's text, spaces included ([OcrGeometry.alignChars]). */
    private fun alignedChars(line: Text.Line, lineBox: BoundingBox): List<BoundingBox>? {
        val words = line.elements.mapNotNull { element ->
            val box = element.boundingBox ?: return@mapNotNull null
            val symbols = element.symbols.mapNotNull { s -> s.boundingBox?.let { s.text to BoundingBox.from(it) } }
            OcrGeometry.WordBoxes(element.text, BoundingBox.from(box), symbols.takeIf { it.size == element.symbols.size }.orEmpty())
        }
        return OcrGeometry.alignChars(line.text, words, lineBox)
    }

    /**
     * Second pass: for each consolidated line, crop + upscale and re-run recognition,
     * keeping the better reading. Capped to avoid long scans.
     */
    private suspend fun refine(bitmap: Bitmap, merged: List<OcrPostProcessor.MergedLine>, original: List<OcrLine>): List<OcrLine> {
        val lines = merged.map { OcrLine(text = it.text, box = it.box) }
        val refined = ArrayList<OcrLine>(lines.size)
        val candidates = lines
            .sortedByDescending { it.box.width.toLong() * it.box.height }
            .take(MAX_REFINE_LINES)
        val refinedByKey = HashMap<String, String>()
        for (line in candidates) {
            val crop = ImagePreprocessor.crop(bitmap, line.box, padding = 16)
            val scaled = ImagePreprocessor.upscaleForOcr(crop, minWidth = crop.width * 2)
            try {
                val result = recognizeOnce(scaled)
                if (result.isEmpty()) continue
                val candidateText = OcrPostProcessor.merge(result, dropNoise = dropNoise, profile = profile).joinToString("\n") { it.text }
                if (profile.score(candidateText) > profile.score(line.text) + SCORE_MARGIN) {
                    refinedByKey[keyOf(line)] = candidateText
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed optional second pass keeps the original read.
            } finally {
                if (scaled !== crop && scaled !== bitmap) scaled.recycle()
                if (crop !== bitmap) crop.recycle()
            }
        }
        if (refinedByKey.isEmpty()) return original
        for (line in lines) {
            val better = refinedByKey[keyOf(line)]
            refined += if (better != null) line.copy(text = better) else line
        }
        return refined
    }

    private fun keyOf(line: OcrLine): String = "${line.box.left}:${line.box.top}:${line.box.width}:${line.box.height}"

    companion object {
        private const val MAX_REFINE_LINES = 6
        private const val SCORE_MARGIN = 1.5
    }
}

/** ML Kit Japanese: the original engine, still used directly by book mode. */
class MlKitJapaneseOcr(
    twoPass: Boolean = true,
    dropNoise: Boolean = true,
) : MlKitOcr(MlKitScript.JAPANESE, twoPass, dropNoise, TextProfile.JAPANESE)

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    addOnCanceledListener { if (cont.isActive) cont.cancel() }
}
