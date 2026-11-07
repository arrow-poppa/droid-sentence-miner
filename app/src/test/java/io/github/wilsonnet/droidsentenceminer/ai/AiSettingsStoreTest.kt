package io.github.wilsonnet.droidsentenceminer.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiSettingsStoreTest {
    private lateinit var dir: File
    private val cipher = FakeCipher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() { dir = Files.createTempDirectory("ai-store").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun store() = AiSettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "ai_settings.preferences_pb") },
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "ai_secrets.preferences_pb") },
        cipher,
    )

    @Test
    fun `defaults send nothing unprompted`() = blocking {
        val s = store().current()
        assertFalse(s.enabled)
        assertFalse(s.autoExplain)
        assertFalse(s.fallbackForUnknownWords)
        assertFalse(s.allowInsecureLocalEndpoint)
        assertEquals(AiSettings.DEFAULT_USER_PROMPT, s.userPrompt)
        assertEquals(0.7, s.temperature, 0.0)
    }

    @Test
    fun `settings round trip per provider`() = blocking {
        val store = store()
        store.update {
            it.copy(
                enabled = true, provider = AiProviderType.OPENROUTER, systemPrompt = "sys", temperature = 1.2, streaming = false,
                autoExplain = true, fallbackForUnknownWords = true, allowInsecureLocalEndpoint = true,
                providers = it.providers + (AiProviderType.OPENROUTER to ProviderSettings(
                    model = "m", routingMode = "only", providerSlugs = "a,b", allowFallbacks = false, requestBodyJson = "{}",
                )) + (AiProviderType.GEMINI to ProviderSettings(thinkingLevel = "LOW")),
            )
        }
        val s = store.current()
        assertEquals(AiProviderType.OPENROUTER, s.provider)
        assertTrue(s.enabled && s.autoExplain && s.fallbackForUnknownWords && s.allowInsecureLocalEndpoint && !s.streaming)
        assertEquals("sys", s.systemPrompt)
        assertEquals(1.2, s.temperature, 0.0)
        assertEquals("only", s.of(AiProviderType.OPENROUTER).routingMode)
        assertEquals("a,b", s.of(AiProviderType.OPENROUTER).providerSlugs)
        assertFalse(s.of(AiProviderType.OPENROUTER).allowFallbacks)
        assertEquals("LOW", s.of(AiProviderType.GEMINI).thinkingLevel)
        assertTrue(s.of(AiProviderType.OPENAI).allowFallbacks)
    }

    @Test
    fun `api key is encrypted at rest and never in the settings file`() = blocking {
        val store = store()
        store.update { it.copy(enabled = true) }
        store.setApiKey(AiProviderType.OPENAI, "  $TEST_KEY  ")
        assertEquals(TEST_KEY, store.current().of(AiProviderType.OPENAI).apiKey.value)
        assertTrue(store.providersWithKey().contains(AiProviderType.OPENAI))
        assertTrue(store.currentWithoutKeys().of(AiProviderType.OPENAI).apiKey.isBlank)
        for (f in dir.listFiles()!!) {
            val bytes = f.readBytes()
            assertFalse(f.name, String(bytes, Charsets.ISO_8859_1).contains(TEST_KEY))
            assertFalse(f.name, String(bytes, Charsets.ISO_8859_1).contains(TEST_KEY.reversed()))
        }
        assertTrue(File(dir, "ai_secrets.preferences_pb").exists())
    }

    @Test
    fun `keys are kept per provider and a blank key deletes`() = blocking {
        val store = store()
        store.setApiKey(AiProviderType.OPENAI, "k1")
        store.setApiKey(AiProviderType.GEMINI, "k2")
        assertEquals("k1", store.current().of(AiProviderType.OPENAI).apiKey.value)
        assertEquals("k2", store.current().of(AiProviderType.GEMINI).apiKey.value)
        store.setApiKey(AiProviderType.OPENAI, "   ")
        assertTrue(store.current().of(AiProviderType.OPENAI).apiKey.isBlank)
        assertEquals("k2", store.current().of(AiProviderType.GEMINI).apiKey.value)
    }

    @Test
    fun `a key that can no longer be decrypted reads as empty`() = blocking {
        val store = store()
        store.setApiKey(AiProviderType.OPENAI, TEST_KEY)
        cipher.lost = true
        assertTrue(store.current().of(AiProviderType.OPENAI).apiKey.isBlank)
        assertFalse(AiConfigCheck.isConfigured(store.current().copy(enabled = true)))
    }

    @Test
    fun `clearAll wipes settings and keys`() = blocking {
        val store = store()
        store.update { it.copy(enabled = true) }
        store.setApiKey(AiProviderType.OPENAI, TEST_KEY)
        store.clearAll()
        assertFalse(store.current().enabled)
        assertTrue(store.providersWithKey().isEmpty())
    }
}
