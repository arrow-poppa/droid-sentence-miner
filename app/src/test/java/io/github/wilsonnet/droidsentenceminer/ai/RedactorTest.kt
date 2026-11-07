package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactorTest {
    @Test
    fun headersAreScrubbedEvenAfterLiteralKeyReplacement() {
        val message = Redactor(TEST_KEY).redact("Invalid key $TEST_KEY. Authorization: Bearer $TEST_KEY")
        assertFalse(message.contains(TEST_KEY))
        assertFalse(message.contains("Authorization", ignoreCase = true))
        assertFalse(message.contains("Bearer", ignoreCase = true))
    }

    @Test
    fun shortKeysAreAlsoSecret() {
        assertEquals("key=***", Redactor("123").redact("key=123"))
    }
}
