package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Bounded in-memory LRU of finished explanations. Never persisted (sentences are private). */
internal class ExplanationCache(private val maxEntries: Int = 64) {
    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: String): String? = map[key]

    @Synchronized fun put(key: String, text: String) {
        map[key] = text
    }

    @Synchronized fun clear() = map.clear()

    @get:Synchronized val size: Int get() = map.size

    companion object {
        /**
         * SHA-256 over the *effective* request: provider, URL (endpoint and Gemini model path), the
         * JSON body (model, system+user prompts after interpolation, temperature, reasoning,
         * routing, custom body) without the `stream` flag, and the raw target/sentence/language.
         * Changing the model, a prompt or any option therefore misses; the API key is not part of it.
         */
        fun keyOf(built: BuiltRequest, request: AiRequest): String {
            val body = JsonObject(built.body.filterKeys { it != "stream" })
            val parts = listOf(
                built.provider.name, built.url.toString(), body.toString(),
                request.target, request.sentence, request.language.orEmpty(),
            )
            val digest = MessageDigest.getInstance("SHA-256")
            for (p in parts) { // length-prefixed so field boundaries cannot be forged
                val bytes = p.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray())
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
