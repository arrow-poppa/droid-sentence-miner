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
}
