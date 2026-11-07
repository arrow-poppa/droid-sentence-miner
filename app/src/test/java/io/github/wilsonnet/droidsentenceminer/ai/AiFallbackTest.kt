package io.github.wilsonnet.droidsentenceminer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFallbackTest {
    @Test
    fun `languages without word boundaries are not supported by the token scanner`() {
        for (l in listOf("ja", "zh", "zh-Hans", "zh-Hant", "yue", "th", "lo", "km", "my", "bo", "JA", "zh_TW")) {
            assertFalse(l, AiFallback.isLanguageSupported(l))
        }
        for (l in listOf("en", "es", "ko", "ru", "ar", "pt-BR", "vi")) assertTrue(l, AiFallback.isLanguageSupported(l))
        assertFalse(AiFallback.isLanguageSupported(null))
        assertFalse(AiFallback.isLanguageSupported(""))
    }

    @Test
    fun `token keeps apostrophes and hyphens inside a word`() {
        assertEquals(AiFallback.Token("don't", 5), AiFallback.token("don't stop"))
        assertEquals(AiFallback.Token("don't", 5), AiFallback.token("don’t stop"))
        assertEquals(AiFallback.Token("well-known", 10), AiFallback.token("well-known fact"))
        assertEquals(AiFallback.Token("Straße", 6), AiFallback.token("Straße 12"))
        assertEquals(AiFallback.Token("слово", 5), AiFallback.token("слово, другое"))
    }

    @Test
    fun `edge separators are stripped from the term but kept in the range`() {
        assertEquals(AiFallback.Token("quoted", 7), AiFallback.token("'quoted'"))
        assertEquals(AiFallback.Token("end", 3), AiFallback.token("end- next"))
    }

    @Test
    fun `no token for digits, punctuation or empty text`() {
        assertNull(AiFallback.token("123 abc"))
        assertNull(AiFallback.token("--"))
        assertNull(AiFallback.token(", word"))
        assertNull(AiFallback.token(""))
    }

    @Test
    fun `target prefers the selected surface in every language`() {
        assertEquals("食べさせられた", AiFallback.target("ja", "食べさせられた", "食べさせられた後で"))
        assertEquals("running", AiFallback.target("en", " running ", "running late"))
    }

    @Test
    fun `delimiting languages derive a target from the scan text`() {
        assertEquals("running", AiFallback.target("en", null, "running late"))
        assertNull(AiFallback.target("en", null, "123"))
        assertNull(AiFallback.target("en", null, null))
    }

    @Test
    fun `ja zh th never grab a whole phrase from scan text`() {
        assertNull(AiFallback.target("ja", null, "吾輩は猫である。名前はまだ無い。"))
        assertNull(AiFallback.target("zh", null, "我喜欢学习中文"))
        assertNull(AiFallback.target("th", null, "สวัสดีครับผม"))
        assertNull(AiFallback.target("ja", "  ", "吾輩は猫である"))
    }

    @Test
    fun `offer only when enabled and no local entry exists`() {
        assertTrue(AiFallback.shouldOffer(fallbackEnabled = true, hasLocalEntry = false))
        assertFalse(AiFallback.shouldOffer(fallbackEnabled = true, hasLocalEntry = true))
        assertFalse(AiFallback.shouldOffer(fallbackEnabled = false, hasLocalEntry = false))
    }
}
