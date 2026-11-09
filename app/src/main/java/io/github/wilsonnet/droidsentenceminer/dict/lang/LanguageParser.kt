package io.github.wilsonnet.droidsentenceminer.dict.lang

import io.github.wilsonnet.droidsentenceminer.dict.JapaneseDeinflector
import java.util.concurrent.ConcurrentHashMap

/** How a [LookupCandidate] relates to the tapped surface; drives ranking (lower [rank] first). */
enum class CandidateKind(val rank: Int) {
    /** The surface itself. */
    EXACT(0),

    /** A safe normalization: case folding, NFC, apostrophe/hyphen/diacritic variants. */
    NORMALIZED(1),

    /** A regular deinflection (-ing, plural, elision, clitic, …). */
    DEINFLECTION(2),

    /** An irregular form replaced as a whole (went → go, mice → mouse). */
    IRREGULAR(3),
}

/**
 * A form to look up for a selected surface. [conditions] are the part-of-speech conditions a
 * dictionary entry must satisfy (Yomitan `rules`; empty = any), [reasons] the deinflection chain
 * (empty for the surface and its normalizations). [normalizationOrder] breaks ties within a
 * kind: English hyphen spellings precede space-separated (1), then joined (2) variants.
 */
data class LookupCandidate(
    val text: String,
    val conditions: Set<String>,
    val reasons: List<String>,
    val kind: CandidateKind = if (reasons.isEmpty()) CandidateKind.EXACT else CandidateKind.DEINFLECTION,
    val normalizationOrder: Int = 0,
)

/**
 * What a tap selected. [surface] is exactly `text.substring(start, end)` (never the lemma);
 * [start]/[end] are UTF-16 offsets into the original [text]; [candidates] are the forms to look
 * up for it, best first; [analyzer] names the strategy that picked the range.
 */
data class TextSelection(
    val surface: String,
    val language: String,
    val text: String,
    val start: Int,
    val end: Int,
    val candidates: List<LookupCandidate>,
    val analyzer: String,
)

interface LanguageParser {
    val code: String

    /** Ordered: exact surface, safe normalizations, deinflections, irregulars. */
    fun candidates(surface: String): List<LookupCandidate>

    /**
     * Candidates for a multi-word span being probed as an expression (phrasal verb, compound):
     * defaults to [candidates]; languages with heavy deinflectors only normalize here, so probing
     * 15 spans around a tap stays cheap.
     */
    fun expressionCandidates(span: String): List<LookupCandidate> = candidates(span)
}

/** How a language's text is cut into words for taps and scans. */
enum class SegmentStrategy {
    /** Existing Japanese path (all-substrings scan, Yomitan deinflector). */
    JAPANESE,

    /** Words are runs of letters/marks/digits (en, pt, ru, ar, de, es, fr, it, ko, generic). */
    SPACE_DELIMITED,

    /** No spaces: dictionary-aware longest match covering the tap (zh, yue, th, lo, km, my). */
    CJK_LONGEST_MATCH,
}

object LanguageParsers {
    private val cache = ConcurrentHashMap<String, LanguageParser>()

    /** BCP-47 base subtag, lower case: `zh-Hant`, `ZH_cn` → `zh`; blank → `ja`. */
    fun normalizeCode(code: String): String {
        val base = code.trim().lowercase().replace('_', '-').substringBefore('-')
        return when (base) {
            "" -> "ja"
            "cmn" -> "zh"
            "jpn" -> "ja"
            "eng" -> "en"
            else -> base
        }
    }

    fun forLanguage(code: String): LanguageParser {
        val normalized = normalizeCode(code)
        return cache.getOrPut(normalized) { Parsers.create(normalized) }
    }

    fun strategy(code: String): SegmentStrategy = when (normalizeCode(code)) {
        "ja" -> SegmentStrategy.JAPANESE
        "zh", "yue", "th", "lo", "km", "my" -> SegmentStrategy.CJK_LONGEST_MATCH
        else -> SegmentStrategy.SPACE_DELIMITED
    }

    /** Name of the strategy/engine behind [TextSelection.analyzer]. */
    fun analyzer(code: String): String = Parsers.analyzerName(normalizeCode(code))
}

/** The existing Japanese deinflector behind the [LanguageParser] interface (`selectionAt` only). */
internal class JapaneseParser : LanguageParser {
    override val code: String = "ja"

    override fun candidates(surface: String): List<LookupCandidate> {
        val out = LinkedHashMap<String, LookupCandidate>()
        out[surface] = LookupCandidate(surface, emptySet(), emptyList(), CandidateKind.EXACT)
        for (candidate in JapaneseDeinflector.deinflect(surface)) {
            if (candidate.term.isEmpty() || candidate.term in out) continue
            // The Japanese engine keeps its conditions as a bit mask; the lookup path never converts them.
            out[candidate.term] = LookupCandidate(candidate.term, emptySet(), candidate.reasons.toList())
        }
        return out.values.toList()
    }
}
