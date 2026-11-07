package io.github.wilsonnet.droidsentenceminer.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.system.measureTimeMillis

/**
 * PaddleOCR (PP-OCRv6 detector + PP-OCRv6/v5 recognizer) on ONNX Runtime's CPU provider, the same
 * runtime meikiocr already uses — no native code of our own. Pre/post-processing lives in
 * [PaddleDecoder]. One instance holds two sessions; [OcrEngineRegistry] keeps at most one alive.
 *
 * Character boxes: PaddleOCR reports none. Each character's x range is estimated from the CTC time
 * steps it was emitted at (one step ≈ 8 input px), which is closer than an even split for
 * proportional fonts but still approximate.
 */
class PaddleOcr private constructor(
    private val env: OrtEnvironment,
    private val detector: OrtSession,
    private val recognizer: OrtSession,
    private val chars: List<String>,
    val recognizerModel: PaddleRecognizer,
    val detectorModel: PaddleComponent,
    private val options: PaddleDecoder.DetOptions,
) : OcrEngine, Closeable {

    override val name: String = "PaddleOCR ${recognizerModel.component.label}"

    data class Timings(val detMs: Long = 0, val recMs: Long = 0, val boxes: Int = 0)

    @Volatile
    var lastTimings = Timings()
        private set

    @Volatile
    private var closed = false

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        var lines: List<OcrLine> = emptyList()
        val elapsed = measureTimeMillis { lines = synchronized(this@PaddleOcr) { run(bitmap) } }
        val profile = recognizerModel.profile
        val text = OcrPostProcessor.toSentenceText(OcrPostProcessor.merge(lines, profile = profile), profile)
        OcrResult(text = text, lines = lines, elapsedMs = elapsed)
    }

    private fun run(bitmap: Bitmap): List<OcrLine> {
        check(!closed) { "PaddleOCR engine was closed" }
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val detStart = System.currentTimeMillis()
        val (dw, dh) = PaddleDecoder.detInputSize(source.width, source.height, options.limitSide)
        val detScaled = Bitmap.createScaledBitmap(source, dw, dh, true)
        val detPixels = IntArray(dw * dh).also { detScaled.getPixels(it, 0, dw, 0, 0, dw, dh) }
        if (detScaled !== source) detScaled.recycle()
        val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(PaddleDecoder.detTensor(detPixels, dw, dh)), longArrayOf(1, 3, dh.toLong(), dw.toLong())).use { input ->
            detector.run(mapOf(detector.inputNames.first() to input)).use { out ->
                val tensor = out.get(0) as OnnxTensor
                val buffer = tensor.floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
        val boxes = PaddleDecoder.boxes(prob, dw, dh, options)
        val detMs = System.currentTimeMillis() - detStart

        val recStart = System.currentTimeMillis()
        val sx = source.width.toFloat() / dw
        val sy = source.height.toFloat() / dh
        val lines = boxes.mapNotNull { box ->
            val left = floor(box.left * sx).toInt().coerceIn(0, source.width - 1)
            val top = floor(box.top * sy).toInt().coerceIn(0, source.height - 1)
            val right = ceil(box.right * sx).toInt().coerceIn(left + 1, source.width)
            val bottom = ceil(box.bottom * sy).toInt().coerceIn(top + 1, source.height)
            recognizeLine(source, BoundingBox(left, top, right, bottom))
        }
        if (source !== bitmap) source.recycle()
        lastTimings = Timings(detMs, System.currentTimeMillis() - recStart, boxes.size)
        return lines
    }

    private fun recognizeLine(source: Bitmap, box: BoundingBox): OcrLine? {
        val w = PaddleDecoder.recWidth(box.width, box.height)
        val crop = Bitmap.createBitmap(source, box.left, box.top, box.width, box.height)
        val scaled = Bitmap.createScaledBitmap(crop, w, PaddleDecoder.REC_HEIGHT, true)
        if (crop !== source && crop !== scaled) crop.recycle()
        val pixels = IntArray(w * PaddleDecoder.REC_HEIGHT).also { scaled.getPixels(it, 0, w, 0, 0, w, PaddleDecoder.REC_HEIGHT) }
        if (scaled !== source) scaled.recycle()
        val decoded = OnnxTensor.createTensor(env, FloatBuffer.wrap(PaddleDecoder.recTensor(pixels, w)), longArrayOf(1, 3, PaddleDecoder.REC_HEIGHT.toLong(), w.toLong())).use { input ->
            recognizer.run(mapOf(recognizer.inputNames.first() to input)).use { out ->
                val tensor = out.get(0) as OnnxTensor
                val shape = tensor.info.shape // [1, T, C]
                val buffer = tensor.floatBuffer
                val data = FloatArray(buffer.remaining()).also { buffer.get(it) }
                PaddleDecoder.ctcDecode(data, shape[1].toInt(), shape[2].toInt(), chars)
            }
        }
        if (decoded.chars.isEmpty()) return null
        // Step t covers roughly [t, t+1) / steps of the resized width, i.e. of the crop.
        fun charBox(c: PaddleDecoder.DecodedChar): BoundingBox {
            val l = box.left + box.width * c.firstStep / decoded.steps
            val r = box.left + box.width * (c.lastStep + 1) / decoded.steps
            return BoundingBox(l, box.top, maxOf(r, l + 1), box.bottom)
        }
        var ordered = decoded.chars.filter { it.text.isNotBlank() || it.text == " " }
        if (recognizerModel.rightToLeft) ordered = PaddleDecoder.restoreRtl(ordered) { it.text }
        val text = ordered.joinToString("") { it.text }.trim()
        if (text.isEmpty()) return null
        // Character boxes only when each decoded unit is one UTF-16 char, so they index the text.
        val trimmedStart = ordered.indexOfFirst { it.text != " " }
        val trimmed = ordered.subList(trimmedStart, ordered.indexOfLast { it.text != " " } + 1)
        val boxes = if (trimmed.all { it.text.length == 1 }) trimmed.map(::charBox) else null
        return OcrLine(text = text, box = box, confidence = decoded.confidence, chars = boxes)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { detector.close() }
            runCatching { recognizer.close() }
        }
    }

    companion object {
        private const val TAG = "DSM-Paddle"

        /** Missing models → [OcrUnavailableException] with what to download (never an empty result). */
        fun load(
            context: Context,
            recognizer: PaddleRecognizer,
            detector: PaddleComponent = PaddleComponent.DET_TINY,
            threads: Int = 3,
        ): PaddleOcr {
            val missing = listOf(detector, recognizer.component).filterNot { PaddleModels.installed(context, it) }
            if (missing.isNotEmpty()) {
                throw OcrUnavailableException("PaddleOCR needs ${missing.joinToString { it.label }} — download it in Settings → OCR")
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            val started = System.currentTimeMillis()
            val det = env.createSession(PaddleModels.modelFile(context, detector).absolutePath, opts)
            val rec = try {
                env.createSession(PaddleModels.modelFile(context, recognizer.component).absolutePath, opts)
            } catch (e: Exception) {
                det.close()
                throw e
            }
            val chars = PaddleModels.dictFile(context, recognizer.component).readLines()
            Log.i(TAG, "loaded ${detector.id}+${recognizer.id} (${chars.size} chars) in ${System.currentTimeMillis() - started} ms")
            return PaddleOcr(env, det, rec, chars, recognizer, detector, PaddleDecoder.DetOptions())
        }
    }
}
