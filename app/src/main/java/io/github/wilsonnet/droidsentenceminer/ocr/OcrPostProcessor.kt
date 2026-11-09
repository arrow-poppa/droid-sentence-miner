package io.github.wilsonnet.droidsentenceminer.ocr

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cleans up raw OCR line detections the way GSM/OwOCR does for visual novels:
 * furigana filtering, UI-noise filtering, line merging and text normalization.
 *
 * The [TextProfile] decides what is "the text": [TextProfile.JAPANESE] (the default) keeps the
 * original Japanese rules byte for byte; other profiles use script-based noise filtering, NFKC
 * without the full-width punctuation mapping, no furigana filter, and space-joined rows for
 * space-delimited scripts.
 */
object OcrPostProcessor {

    data class MergedLine(val text: String, val box: BoundingBox)

    fun merge(
        rawLines: List<OcrLine>,
        dropNoise: Boolean = true,
        profile: TextProfile = TextProfile.JAPANESE,
    ): List<MergedLine> {
        if (rawLines.isEmpty()) return emptyList()

        val normalized = rawLines
            .mapNotNull { line ->
                val text = normalize(line.text, profile)
                if (text.isBlank()) null else line.copy(text = text)
            }
        if (normalized.isEmpty()) return emptyList()

        val furiganaFiltered = if (profile.isJapanese) dropFurigana(normalized) else normalized
        val hasTarget = furiganaFiltered.any { profile.ratio(it.text) >= 0.35 }
        val noiseFiltered = if (hasTarget && dropNoise) {
            furiganaFiltered.filter { profile.ratio(it.text) >= 0.20 || it.text.length <= 1 }
        } else {
            furiganaFiltered
        }
        if (noiseFiltered.isEmpty()) return emptyList()

        val sorted = noiseFiltered.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val group = groups.lastOrNull()
            if (group != null && sameTextRow(group, line)) {
                group += line
            } else {
                groups += mutableListOf(line)
            }
        }

        val merged = groups.map { group ->
            val ordered = if (profile.rightToLeft) group.sortedByDescending { it.box.left } else group.sortedBy { it.box.left }
            val text = joinRow(ordered.map { it.text }, profile)
            val box = ordered.map { it.box }.reduce { acc, box -> acc.union(box) }
            MergedLine(text, box)
        }

        // Remove duplicated text produced by overlapping detections.
        val seen = HashSet<String>()
        return merged.filter { seen.add(it.text) }
    }

    fun toSentenceText(merged: List<MergedLine>, profile: TextProfile = TextProfile.JAPANESE): String {
        if (!profile.isJapanese) return merged.joinToString("\n") { it.text }.trim()
        return merged.joinToString("\n") { OcrTextFixes.apply(it.text) }.trim()
    }

    private fun sameTextRow(group: List<OcrLine>, candidate: OcrLine): Boolean {
        val ref = group.last()
        val refCenter = ref.box.centerY
        val candCenter = candidate.box.centerY
        val avgHeight = (ref.box.height + candidate.box.height) / 2f
        if (abs(refCenter - candCenter) > max(avgHeight * 0.55f, 10f)) return false

        val horizontalGap = when {
            candidate.box.left >= ref.box.right -> candidate.box.left - ref.box.right
            ref.box.left >= candidate.box.right -> ref.box.left - candidate.box.right
            else -> 0
        }
        val allowedGap = max(avgHeight * 0.9f, 24f)
        return horizontalGap <= allowedGap
    }

    /**
     * A conservative furigana heuristic: kana-only lines that are much smaller than
     * the median line and overlap a taller line horizontally are dropped.
     */
    private fun dropFurigana(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2) return lines
        val heights = lines.map { it.box.height.toFloat() }.sorted()
        val median = heights[heights.size / 2]
        if (median <= 0f) return lines
        return lines.filterNot { line ->
            val height = line.box.height
            val isSmall = height < median * 0.62f
            val isKanaOnly = line.text.all { JapaneseTextScore.isJapanese(it) } &&
                line.text.none { TermLookupIsKanji(it) }
            val overlapsTaller = lines.any { other ->
                other !== line && other.box.height > height * 1.4f &&
                    horizontalOverlap(line.box, other.box) > 0.3f
            }
            isSmall && isKanaOnly && overlapsTaller && line.text.length <= 10
        }
    }

    private fun TermLookupIsKanji(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    private fun horizontalOverlap(a: BoundingBox, b: BoundingBox): Float {
        val overlap = (min(a.right, b.right) - max(a.left, b.left)).toFloat()
        val minWidth = min(a.width, b.width).toFloat()
        if (minWidth <= 0f) return 0f
        return (overlap / minWidth).coerceIn(0f, 1f)
    }

    private fun joinRow(parts: List<String>, profile: TextProfile): String {
        if (profile.isJapanese) return joinJapanese(parts)
        if (profile.spaceDelimited) return parts.joinToString(" ").trim()
        // Chinese / Thai: glue, except between two non-target words (Latin names inside Chinese).
        val sb = StringBuilder()
        for (part in parts) {
            if (sb.isNotEmpty()) {
                val prev = sb.last()
                val next = part.first()
                if (prev.isLetterOrDigit() && !profile.isTarget(prev) && next.isLetterOrDigit() && !profile.isTarget(next)) sb.append(' ')
            }
            sb.append(part)
        }
        return sb.toString().trim()
    }

    private fun joinJapanese(parts: List<String>): String {
        val sb = StringBuilder()
        for (part in parts) {
            if (sb.isEmpty()) {
                sb.append(part)
                continue
            }
            val prev = sb.last()
            val next = part.first()
            val needSpace = prev.isLetterOrDigit() && !JapaneseTextScore.isJapanese(prev) &&
                next.isLetterOrDigit() && !JapaneseTextScore.isJapanese(next)
            if (needSpace) sb.append(' ')
            sb.append(part)
        }
        return sb.toString().trim()
    }

    fun normalize(text: String, profile: TextProfile): String {
        if (profile.isJapanese) return normalize(text)
        // NFKC folds Arabic presentation forms, ligatures and full-width Latin; no punctuation mapping.
        val t = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("[ \\t]+"), " ")
        return if (profile.spaceDelimited) t.trim() else t.replace(Regex("(?<=[\\p{IsHan}]) (?=[\\p{IsHan}])"), "").trim()
    }

    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = t.replace('\u00A0', ' ')
            .replace('\u3000', ' ')
        t = t.map { ch ->
            when (ch) {
                '\uFF0D', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015' -> 'ー'
                '\uFF5E', '\u301C' -> '〜'
                '!' -> '！'
                '?' -> '？'
                else -> ch
            }
        }.joinToString("")
        t = t.replace(Regex("[ \\t]+"), " ")
        // Drop spaces between Japanese characters / around Japanese punctuation.
        t = t.replace(Regex("(?<=[$JP_CHARS]) (?=[$JP_CHARS])"), "")
        t = t.replace(Regex("(?<=[$JP_CHARS]) "), "")
        t = t.replace(Regex(" (?=[$JP_CHARS])"), "")
        return t.trim()
    }

    private const val JP_CHARS = "\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}。、！？「」『』（）…・ー〜"
}
