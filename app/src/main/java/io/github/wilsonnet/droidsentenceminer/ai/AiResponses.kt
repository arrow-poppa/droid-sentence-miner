package io.github.wilsonnet.droidsentenceminer.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Response/chunk text extraction (ports of `_stringifyMessageContent`, `_extractOpenAIStreamDelta`, `_extractGeminiText`). */
internal object AiResponses {
    /** String | array of parts (string, `{text}` or `{content}`) | single part object. */
    fun stringifyContent(content: JsonElement?): String = when (content) {
        null -> ""
        is JsonArray -> content.joinToString("") { partText(it) }
        else -> partText(content)
    }

    private fun partText(part: JsonElement): String = when (part) {
        is JsonPrimitive -> if (part.isString) part.content else ""
        is JsonObject -> (part["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (part["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: ""
        else -> ""
    }

    private fun firstChoice(payload: JsonElement): JsonObject? =
        ((payload as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject

    /** Whole (non-streaming) OpenAI-compatible answer. */
    fun openAiText(payload: JsonElement): String {
        val choice = firstChoice(payload) ?: return ""
        val message = choice["message"] as? JsonObject
        val content = message?.get("content")?.takeUnless { it is kotlinx.serialization.json.JsonNull } ?: choice["text"]
        return stringifyContent(content)
    }

    /** One streamed chunk; `reasoning_content` is thinking, not the answer, and is ignored. */
    fun openAiDelta(payload: JsonElement): String {
        val choice = firstChoice(payload) ?: return ""
        val delta = choice["delta"]
        if (delta is JsonObject) {
            val content = delta["content"]
            return if (content == null || content is kotlinx.serialization.json.JsonNull) "" else stringifyContent(content)
        }
        return (choice["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    }

    /** Gemini answer text of a whole response or one stream chunk, skipping `thought` parts. */
    fun geminiText(payload: JsonElement): String {
        val candidate = ((payload as? JsonObject)?.get("candidates") as? JsonArray)?.firstOrNull() as? JsonObject ?: return ""
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (part in parts) {
            if (part !is JsonObject) continue
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
            (part["text"] as? JsonPrimitive)?.takeIf { it.isString }?.let { sb.append(it.content) }
        }
        return sb.toString()
    }

    /** `blockReason` of a Gemini response that was refused, if any. */
    fun geminiBlockReason(payload: JsonElement): String? =
        (((payload as? JsonObject)?.get("promptFeedback") as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull

    /** The `error` member of a provider payload, as (http-ish code, message), or null if there is none. */
    fun errorOf(payload: JsonElement): Pair<Int?, String?>? {
        val error = (payload as? JsonObject)?.get("error") ?: return null
        if (error is kotlinx.serialization.json.JsonNull) return null
        if (error is JsonObject) {
            val code = (error["code"] as? JsonPrimitive)?.intOrNull
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull
            return code to message
        }
        return null to (error as? JsonPrimitive)?.contentOrNull
    }
}
