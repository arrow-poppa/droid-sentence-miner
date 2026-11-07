package io.github.wilsonnet.droidsentenceminer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OcrGeometryTest {
    @Test
    fun upscaledLineAndSymbolBoxesReturnToSourceCrop() {
        val line = OcrLine("Hi", BoundingBox(20, 40, 100, 80), chars = listOf(BoundingBox(20, 40, 60, 80), BoundingBox(60, 40, 100, 80)))
        val source = OcrGeometry.toSource(line, 400, 200, 200, 100)
        assertEquals(BoundingBox(10, 20, 50, 40), source.box)
        assertEquals(listOf(BoundingBox(10, 20, 30, 40), BoundingBox(30, 20, 50, 40)), source.chars)
        assertEquals("Hi", source.text)
    }

    @Test
    fun independentRoundedScaleFactorsStayInsideSourceImage() {
        val line = OcrLine("x", BoundingBox(-1, 1, 1002, 501), confidence = 0.8f)
        val mapped = OcrGeometry.toSource(line, 1000, 500, 333, 167)
        assertEquals(BoundingBox(0, 0, 333, 167), mapped.box)
        assertEquals(0.8f, mapped.confidence)
    }

    @Test
    fun unchangedImageRetainsOriginalRead() {
        val line = OcrLine("text", BoundingBox(0, 0, 10, 10))
        assertSame(line, OcrGeometry.toSource(line, 100, 100, 100, 100))
    }
}
