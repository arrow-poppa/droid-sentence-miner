package io.github.wilsonnet.droidsentenceminer.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin pre/post-processing of PaddleOCR's DB detector and CTC recognizer (unit tested,
 * validated against the desktop reference `tools/paddle-ocr/reference.py`).
 *
 * Model contract (from the pinned `inference.yml` files): BGR input. Detector: ImageNet mean/std on
 * 0..1, both sides multiples of 32, output a [1,1,H,W] probability map. Recognizer: height 48,
 * `(x/255 - 0.5) / 0.5`, output [1,T,C] softmax with class 0 = CTC blank, 1..C-2 = `character_dict`,
 * C-1 = space.
 *
 * Boxes are axis-aligned (DSM's overlay and [BoundingBox] are), so rotated text is read as its
 * bounding rectangle; Paddle's own min-area-rect + perspective crop is not ported.
 */
object PaddleDecoder {

    const val REC_HEIGHT = 48
    const val REC_MAX_WIDTH = 3200
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    data class DetOptions(
        val thresh: Float = 0.3f,
        val boxThresh: Float = 0.6f,
        val unclipRatio: Float = 1.5f,
        val minSize: Int = 3,
        /** Longest side the detector sees; bigger images are scaled down. */
        val limitSide: Int = 960,
        val maxCandidates: Int = 1000,
    )

    data class DetBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float)

    /** Detector input size for a [width]×[height] image: scaled to fit [limitSide], rounded to multiples of 32. */
    fun detInputSize(width: Int, height: Int, limitSide: Int): Pair<Int, Int> {
        val scale = min(1.0, limitSide.toDouble() / max(width, height))
        val w = max(32, (width * scale / 32).roundToInt() * 32)
        val h = max(32, (height * scale / 32).roundToInt() * 32)
        return w to h
    }

    /** ARGB pixels ([w]×[h], already resized) → CHW float BGR, ImageNet-normalized. */
    fun detTensor(pixels: IntArray, w: Int, h: Int): FloatArray {
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val b = (p and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val r = (p shr 16 and 0xFF) / 255f
            out[i] = (b - MEAN[0]) / STD[0]
            out[plane + i] = (g - MEAN[1]) / STD[1]
            out[2 * plane + i] = (r - MEAN[2]) / STD[2]
        }
        return out
    }

    /** Recognizer input width for a crop: height 48, aspect kept, at least 8 and at most [REC_MAX_WIDTH]. */
    fun recWidth(cropWidth: Int, cropHeight: Int): Int =
        ceil(REC_HEIGHT.toDouble() * cropWidth / max(1, cropHeight)).toInt().coerceIn(8, REC_MAX_WIDTH)

    /** ARGB pixels ([w]×48, already resized) → CHW float BGR in [-1, 1]. */
    fun recTensor(pixels: IntArray, w: Int, h: Int = REC_HEIGHT): FloatArray {
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = (p and 0xFF) / 127.5f - 1f
            out[plane + i] = (p shr 8 and 0xFF) / 127.5f - 1f
            out[2 * plane + i] = (p shr 16 and 0xFF) / 127.5f - 1f
        }
        return out
    }

    /**
     * DB post-processing on the [w]×[h] probability map: threshold, 4-connected components, mean
     * probability inside the component's box as score, then Paddle's unclip distance
     * `area × ratio / perimeter` applied to the rectangle. Coordinates are in map pixels.
     */
    fun boxes(prob: FloatArray, w: Int, h: Int, options: DetOptions = DetOptions()): List<DetBox> {
        require(prob.size >= w * h)
        val visited = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<DetBox>()
        for (start in 0 until w * h) {
            if (visited[start] || prob[start] <= options.thresh) continue
            var top = 0
            stack[top++] = start
            visited[start] = true
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) push(i - 1, prob, visited, options.thresh)?.let { stack[top++] = it }
                if (x < w - 1) push(i + 1, prob, visited, options.thresh)?.let { stack[top++] = it }
                if (y > 0) push(i - w, prob, visited, options.thresh)?.let { stack[top++] = it }
                if (y < h - 1) push(i + w, prob, visited, options.thresh)?.let { stack[top++] = it }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (min(bw, bh) < options.minSize) continue
            var sum = 0.0
            for (y in minY..maxY) for (x in minX..maxX) sum += prob[y * w + x]
            val score = (sum / (bw * bh)).toFloat()
            if (score < options.boxThresh) continue
            val d = bw.toFloat() * bh * options.unclipRatio / (2f * (bw + bh))
            out += DetBox(minX - d, minY - d, maxX + 1 + d, maxY + 1 + d, score)
            if (out.size >= options.maxCandidates) break
        }
        return out.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun push(i: Int, prob: FloatArray, visited: BooleanArray, thresh: Float): Int? {
        if (visited[i] || prob[i] <= thresh) return null
        visited[i] = true
        return i
    }

    /** One decoded character and the recognizer time steps it covers (for approximate x positions). */
    data class DecodedChar(val text: String, val firstStep: Int, val lastStep: Int, val probability: Float)

    data class Decoded(val text: String, val confidence: Float?, val chars: List<DecodedChar>, val steps: Int)

    /**
     * Greedy CTC over [probs] laid out as [steps]×[classes]: argmax per step, collapse repeats, drop
     * blank (0). [chars] is the model's character_dict; class C-1 is a space. Confidence = mean
     * probability of the emitted characters (null when nothing was emitted).
     */
    fun ctcDecode(probs: FloatArray, steps: Int, classes: Int, chars: List<String>): Decoded {
        val out = ArrayList<DecodedChar>()
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            var bestP = probs[t * classes]
            for (c in 1 until classes) {
                val p = probs[t * classes + c]
                if (p > bestP) {
                    bestP = p
                    best = c
                }
            }
            if (best != 0 && best == previous && out.isNotEmpty()) {
                val last = out.last()
                out[out.lastIndex] = last.copy(lastStep = t)
            } else if (best != 0) {
                val text = when {
                    best - 1 < chars.size -> chars[best - 1]
                    else -> " "
                }
                out += DecodedChar(text, t, t, bestP)
            }
            previous = best
        }
        val text = out.joinToString("") { it.text }
        val confidence = if (out.isEmpty()) null else out.map { it.probability }.average().toFloat()
        return Decoded(text, confidence, out, steps)
    }

    /**
     * Arabic CTC emits characters in visual (left-to-right) order; logical order is the reverse,
     * except runs of Latin letters, digits and a few symbols, which keep their order (PaddleOCR's
     * `pred_reverse`, as ported in Chimahon's `restorePaddleArabicText`). Works on any list so the
     * per-character boxes are permuted with their characters.
     */
    fun <T> restoreRtl(items: List<T>, textOf: (T) -> String): List<T> {
        val groups = ArrayList<List<T>>()
        val run = ArrayList<T>()
        fun flush() {
            if (run.isNotEmpty()) {
                groups += ArrayList(run)
                run.clear()
            }
        }
        for (item in items) {
            val t = textOf(item)
            val keep = t.length == 1 && t[0].code <= 127 && (t[0].isLetterOrDigit() || t[0] in " :*./%+-")
            if (keep) {
                run += item
            } else {
                flush()
                groups += listOf(item)
            }
        }
        flush()
        return groups.asReversed().flatten()
    }

    fun restoreArabicText(text: String): String = restoreRtl(text.map { it.toString() }) { it }.joinToString("")

    /** The `character_dict` list of a PaddleOCR `inference.yml` (plain and single-quoted YAML scalars). */
    fun parseCharacterDict(yml: String): List<String> {
        val lines = yml.split('\n')
        val start = lines.indexOfFirst { it.trimEnd() == "  character_dict:" }
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        for (line in lines.subList(start + 1, lines.size)) {
            if (!line.startsWith("  - ")) break
            var value = line.substring(4).trimEnd('\r')
            if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length - 1).replace("''", "'")
            }
            out += value
        }
        return out
    }
}
