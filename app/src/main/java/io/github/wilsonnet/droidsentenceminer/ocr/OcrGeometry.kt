package io.github.wilsonnet.droidsentenceminer.ocr

import kotlin.math.ceil
import kotlin.math.floor

/** Map recognition coordinates back to the image passed to OcrEngine.recognize. */
internal object OcrGeometry {
    fun toSource(line: OcrLine, inputWidth: Int, inputHeight: Int, sourceWidth: Int, sourceHeight: Int): OcrLine {
        require(inputWidth > 0 && inputHeight > 0 && sourceWidth > 0 && sourceHeight > 0)
        if (inputWidth == sourceWidth && inputHeight == sourceHeight) return line
        fun map(box: BoundingBox): BoundingBox {
            val sx = sourceWidth.toDouble() / inputWidth
            val sy = sourceHeight.toDouble() / inputHeight
            val left = floor(box.left * sx).toInt().coerceIn(0, sourceWidth - 1)
            val top = floor(box.top * sy).toInt().coerceIn(0, sourceHeight - 1)
            return BoundingBox(
                left, top,
                ceil(box.right * sx).toInt().coerceIn(left + 1, sourceWidth),
                ceil(box.bottom * sy).toInt().coerceIn(top + 1, sourceHeight),
            )
        }
        return line.copy(box = map(line.box), chars = line.chars?.map(::map))
    }

    /** A recognized word (ML Kit element): its text, box and symbol boxes with each symbol's text. */
    data class WordBoxes(val text: String, val box: BoundingBox, val symbols: List<Pair<String, BoundingBox>>)

    /**
     * One box per UTF-16 unit of [text] (a line), so a tap on the screen layer indexes the text.
     * ML Kit reports symbols only for word characters: flattening them drops the spaces, the count
     * no longer matches a line with spaces and the layer fell back to splitting the line evenly,
     * which on proportional fonts lands taps on the neighbouring word. Here each word keeps its
     * symbol boxes (or its own box split evenly if they don't add up to its text), and the gap
     * between two words is shared by the characters between them (spaces, punctuation ML Kit
     * left out). Null when a word's text is not found in order in [text].
     */
    fun alignChars(text: String, words: List<WordBoxes>, lineBox: BoundingBox): List<BoundingBox>? {
        if (text.isEmpty() || words.isEmpty()) return null
        val boxes = arrayOfNulls<BoundingBox>(text.length)
        var cursor = 0
        for (word in words) {
            if (word.text.isEmpty()) continue
            val at = text.indexOf(word.text, cursor)
            if (at < 0) return null
            val own = if (word.symbols.sumOf { it.first.length } == word.text.length && word.symbols.isNotEmpty()) {
                word.symbols.flatMap { (s, b) -> List(s.length) { b } }
            } else {
                splitEvenly(word.box, word.text.length)
            }
            own.forEachIndexed { i, b -> boxes[at + i] = b }
            cursor = at + word.text.length
        }
        // Characters no word claimed: share the gap between their neighbours.
        var i = 0
        while (i < boxes.size) {
            if (boxes[i] != null) {
                i++
                continue
            }
            val start = i
            while (i < boxes.size && boxes[i] == null) i++
            val prev = boxes.getOrNull(start - 1)
            val next = boxes.getOrNull(i)
            val left = prev?.right ?: lineBox.left
            val right = maxOf(next?.left ?: lineBox.right, left + 1)
            val top = minOf(prev?.top ?: lineBox.top, next?.top ?: lineBox.top)
            val bottom = maxOf(prev?.bottom ?: lineBox.bottom, next?.bottom ?: lineBox.bottom)
            splitEvenly(BoundingBox(left, top, right, maxOf(bottom, top + 1)), i - start).forEachIndexed { k, b -> boxes[start + k] = b }
        }
        return boxes.map { it!! }
    }

    private fun splitEvenly(b: BoundingBox, n: Int): List<BoundingBox> = List(n) { k ->
        val l = b.left + b.width * k / n
        BoundingBox(l, b.top, maxOf(b.left + b.width * (k + 1) / n, l + 1), b.bottom)
    }
}
