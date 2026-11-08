package io.github.wilsonnet.droidsentenceminer.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleModeTest {
    @Test
    fun `missing or unknown preferences keep quick lookup as the default`() {
        for (value in listOf(null, "", "unknown")) {
            assertEquals(BubbleMode.QUICK, BubbleMode.fromPreference(value))
        }
    }

    @Test
    fun `existing modes and auto OCR round trip through the shared preference`() {
        assertEquals(BubbleMode.QUICK, BubbleMode.fromPreference("quick"))
        assertEquals(BubbleMode.REGION, BubbleMode.fromPreference("region"))
        assertEquals("auto_ocr", BubbleMode.AUTO_OCR.preferenceValue)
        for (mode in BubbleMode.entries) {
            assertEquals(mode, BubbleMode.fromPreference(mode.preferenceValue))
            assertEquals(mode, BubbleMode.fromPreference(mode.name))
        }
    }
}
