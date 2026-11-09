package io.github.wilsonnet.droidsentenceminer.anki

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordAudioLanguageTest {
    @Test(timeout = 15_000)
    fun nonJapaneseCustomAudioDoesNotFallThroughToJapaneseProviders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val hosts = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                hosts += chain.request().url.host
                check(chain.request().url.host == server.hostName) { "Unexpected pronunciation provider" }
                chain.proceed(chain.request())
            }.build()
            assertNull(WordAudio(client).fetch("running", "", server.url("/word.mp3").toString(), japaneseSources = false))
            assertEquals(listOf(server.hostName), hosts)
            assertEquals(1, server.requestCount)
        }
    }

    @Test(timeout = 15_000)
    fun nonJapaneseAudioWithoutCustomUrlMakesNoRequest() = runBlocking {
        val count = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor {
            count.incrementAndGet()
            error("Unexpected network request")
        }.build()
        assertNull(WordAudio(client).fetch("café", "", japaneseSources = false))
        assertEquals(0, count.get())
    }
}
