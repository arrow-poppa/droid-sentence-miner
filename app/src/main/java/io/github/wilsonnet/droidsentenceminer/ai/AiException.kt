package io.github.wilsonnet.droidsentenceminer.ai

/** A user-presentable failure. Messages never contain the API key or any header. */
class AiException(
    val kind: AiErrorKind,
    message: String,
    val retryable: Boolean,
    /** The provider refused the streaming request itself (the only case that retries without streaming). */
    val streamRejected: Boolean = false,
) : Exception(message) {
    fun toState() = AiExplanationState.Error(message ?: "AI request failed.", retryable, kind)
}

/** Scrubs anything key-shaped out of text that came from a server or an exception. */
class Redactor(vararg secrets: String) {
    private val literals = secrets.map { it.trim() }.filter { it.isNotEmpty() }

    fun redact(text: String, maxLength: Int = 240): String {
        var out = text
        for (s in literals) out = out.replace(s, "***")
        for (p in PATTERNS) out = p.replace(out, "***")
        out = out.replace(Regex("\\s+"), " ").trim()
        return if (out.length > maxLength) out.take(maxLength) + "…" else out
    }

    private companion object {
        val PATTERNS = listOf(
            Regex("(?i)authorization\\s*:\\s*(?:bearer\\s+)?[^\\r\\n,;]+"),
            Regex("(?i)bearer\\s+[^\\s,;]+"),
            Regex("sk-[A-Za-z0-9_-]{8,}"),
            Regex("AIza[0-9A-Za-z_-]{20,}"),
        )
    }
}
