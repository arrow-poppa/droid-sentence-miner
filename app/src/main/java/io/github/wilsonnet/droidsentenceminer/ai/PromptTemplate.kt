package io.github.wilsonnet.droidsentenceminer.ai

/** Placeholder interpolation (`{{target}}`, `{{sentence}}`, optional `{{language}}`). */
object PromptTemplate {
    private val PLACEHOLDER = Regex("""\{\{(target|sentence|language)\}\}""")

    /**
     * Single pass: text inserted for one placeholder is never re-scanned, so a sentence that
     * itself contains "{{target}}" stays literal (the reference chains `.replace` calls).
     */
    fun render(template: String, request: AiRequest): String =
        PLACEHOLDER.replace(template) { m ->
            when (m.groupValues[1]) {
                "target" -> request.target
                "sentence" -> request.sentence
                else -> request.language.orEmpty()
            }
        }

    fun system(settings: AiSettings, request: AiRequest): String =
        render(settings.systemPrompt, request).trim()

    /**
     * The user prompt. The selected surface stays the target; a dictionary headword/reading, when
     * known, is appended as a separate explicit note and never substituted into the prompt.
     */
    fun user(settings: AiSettings, request: AiRequest): String {
        val body = render(settings.userPrompt, request)
        val headword = request.headword?.trim().orEmpty()
        val reading = request.reading?.trim().orEmpty()
        if (headword.isEmpty() && reading.isEmpty()) return body
        val parts = buildList {
            if (headword.isNotEmpty() && headword != request.target.trim()) add("the dictionary headword is '$headword'")
            if (reading.isNotEmpty()) add("its reading is '$reading'")
        }
        if (parts.isEmpty()) return body
        return body + "\n\nDictionary note (a hint only; the selected text is exactly '${request.target}'): " +
            parts.joinToString(", ") + "."
    }
}
