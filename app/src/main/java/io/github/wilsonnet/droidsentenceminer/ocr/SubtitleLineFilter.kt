package io.github.wilsonnet.droidsentenceminer.ocr

/**
 * Auto OCR reads a fixed region every few hundred ms, so background text (signs, labels,
 * UI) drifting through it would otherwise get glued onto the dialogue. Dialogue lines share
 * one font size; rows that are much smaller or larger than the main text, or carry almost no
 * text of the study language ([TextProfile]), are dropped.
 *
 * Pure Kotlin, unit tested.
 */
object SubtitleLineFilter {

    fun filter(
        lines: List<OcrPostProcessor.MergedLine>,
        profile: TextProfile = TextProfile.JAPANESE,
    ): List<OcrPostProcessor.MergedLine> {
        if (lines.size <= 1) return lines
        val minTarget = if (profile.isJapanese) MIN_TARGET else 1
        val dialogue = lines.filter { profile.count(it.text) >= minTarget }
        if (dialogue.isEmpty()) return lines
        // The row with the most study-language text anchors the expected glyph height.
        val anchor = dialogue.maxBy { profile.count(it.text) }.box.height.toFloat()
        if (anchor <= 0f) return lines
        return lines.filter { line ->
            val ratio = line.box.height / anchor
            profile.count(line.text) >= minTarget && ratio in MIN_HEIGHT_RATIO..MAX_HEIGHT_RATIO
        }
    }

    /**
     * Drops engine lines read with very low confidence while another line in the same read is
     * sure: a burned-in subtitle half hidden under a caption box comes back as garbage (0.27 next
     * to 0.97) and would be glued onto the clean line. Engines without confidence are untouched.
     */
    fun dropUnsure(lines: List<OcrLine>): List<OcrLine> {
        val best = lines.mapNotNull { it.confidence }.maxOrNull() ?: return lines
        if (best < SURE_CONFIDENCE) return lines
        return lines.filter { (it.confidence ?: 1f) >= UNSURE_CONFIDENCE }
    }

    private const val SURE_CONFIDENCE = 0.8f
    private const val UNSURE_CONFIDENCE = 0.5f
    private const val MIN_TARGET = 3
    private const val MIN_HEIGHT_RATIO = 0.7f
    private const val MAX_HEIGHT_RATIO = 1.4f
}
