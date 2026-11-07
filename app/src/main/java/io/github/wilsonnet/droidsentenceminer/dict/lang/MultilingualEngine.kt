package io.github.wilsonnet.droidsentenceminer.dict.lang

import io.github.wilsonnet.droidsentenceminer.dict.DictionaryEntity
import io.github.wilsonnet.droidsentenceminer.dict.LookupStore
import io.github.wilsonnet.droidsentenceminer.dict.LruCache
import io.github.wilsonnet.droidsentenceminer.dict.ScannedWord
import io.github.wilsonnet.droidsentenceminer.dict.TermBody
import io.github.wilsonnet.droidsentenceminer.dict.TermHead
import io.github.wilsonnet.droidsentenceminer.dict.TermResult
import io.github.wilsonnet.droidsentenceminer.dict.toTermResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Tap selection, line scans and word lookups for every language except Japanese (which keeps its
 * own path in `TermLookup`). Pure Kotlin over [LookupStore]-backed callbacks, so JVM tests drive it
 * with fake dictionaries.
 *
 * - Space-delimited languages ([SegmentStrategy.SPACE_DELIMITED]): a tap selects the *whole word*
 *   (Fushi's rule; never a suffix such as `orld`), even when no dictionary knows it. A longer
 *   expression (`gave up`, `look forward to`, `gaming-mode`) replaces the word only when a
 *   verified dictionary match covers the tapped word, starts and ends on word boundaries and stays
 *   within [WordSpans.MAX_WORDS] words / [WordSpans.MAX_UNITS] UTF-16 units. No all-substrings scan.
 * - Unspaced scripts ([SegmentStrategy.CJK_LONGEST_MATCH]): the longest dictionary match covering
 *   the tapped character inside a bounded window; a lone character only when nothing longer matches.
 */
internal class MultilingualEngine(
    private val store: LookupStore,
    private val dictionaries: suspend () -> Map<Long, DictionaryEntity>,
    private val queryHeads: suspend (Collection<String>) -> Map<String, List<TermHead>>,
    private val queryBodies: suspend (List<Long>) -> Map<Long, TermBody>,
) {
    class LookupResult(val terms: List<TermResult>)

    /** A row accepted for a candidate. */
    private class Hit(val head: TermHead, val dict: DictionaryEntity, val candidate: LookupCandidate)

    private data class RulesKey(val dictId: Long, val termCount: Long)

    // Keyed by the dictionary's size too, so a re-import never reuses a stale answer; not dropped on
    // reorder/enable (those do not change content) because a false answer costs a full table scan.
    private val rulesCache = ConcurrentHashMap<RulesKey, Boolean>()
    private val expressionCache = LruCache<Pair<String, String>, List<LookupCandidate>>(EXPRESSION_CACHE_SIZE)

    fun invalidateCaches() {
        rulesCache.clear()
        expressionCache.clear()
    }

    private suspend fun dictionaryHasRules(dict: DictionaryEntity): Boolean {
        val key = RulesKey(dict.id, dict.termCount)
        rulesCache[key]?.let { return it }
        val answer = store.dictionaryHasRules(dict.id)
        if (rulesCache.size >= MAX_RULES_CACHE) rulesCache.clear()
        rulesCache[key] = answer
        return answer
    }

    private fun expressionCandidates(parser: LanguageParser, span: String): List<LookupCandidate> {
        val key = parser.code to span
        expressionCache[key]?.let { return it }
        val candidates = parser.expressionCandidates(span).take(MAX_EXPRESSION_CANDIDATES)
        expressionCache[key] = candidates
        return candidates
    }

    /** Rows of enabled dictionaries accepted for each candidate group, in group order. */
    private suspend fun verify(
        groups: List<List<LookupCandidate>>,
        dicts: Map<Long, DictionaryEntity>,
    ): List<List<Hit>> {
        val forms = LinkedHashSet<String>()
        for (group in groups) for (candidate in group) forms += candidate.text
        if (forms.isEmpty()) return groups.map { emptyList() }
        val heads = queryHeads(forms)
        return groups.map { group ->
            val hits = ArrayList<Hit>()
            for (candidate in group) {
                val rows = heads[candidate.text] ?: continue
                for (row in rows) {
                    val dict = dicts[row.dictId] ?: continue
                    if (RuleMatching.accepts(candidate, row, dict) { dictionaryHasRules(it) }) hits += Hit(row, dict, candidate)
                }
            }
            hits
        }
    }

    // --- tap selection -------------------------------------------------------------------------

    suspend fun rangeAt(text: String, index: Int, language: String): IntRange {
        if (text.isEmpty()) return 0 until 0
        val at = index.coerceIn(0, text.length - 1)
        val parser = LanguageParsers.forLanguage(language)
        return when (LanguageParsers.strategy(language)) {
            SegmentStrategy.CJK_LONGEST_MATCH -> unspacedRange(text, at, language, parser)
            else -> spacedRange(text, at, language, parser)
        }
    }

    private fun joinsApostrophes(language: String) = language == "en" || language == "fr" || language == "it"

    private fun codePointRange(text: String, index: Int): IntRange {
        var start = index
        if (Character.isLowSurrogate(text[start]) && start > 0 && Character.isHighSurrogate(text[start - 1])) start--
        val length = Character.charCount(text.codePointAt(start))
        return start until start + length
    }

    private suspend fun spacedRange(text: String, index: Int, language: String, parser: LanguageParser): IntRange {
        val tokens = WordSpans.tokenize(text, joinsApostrophes(language))
        val k = WordSpans.tokenIndexAt(tokens, index)
        if (k < 0) return codePointRange(text, index)
        val token = tokens[k]
        val word = token.start until token.end
        val spans = WordSpans.spansContaining(text, tokens, k)
        if (spans.isEmpty()) return word
        val dicts = dictionaries()
        if (dicts.isEmpty()) return word
        val hits = verify(spans.map { expressionCandidates(parser, it.probe) }, dicts)
        var best: Span? = null
        for ((i, span) in spans.withIndex()) {
            if (hits[i].isEmpty()) continue
            val current = best
            if (current == null || span.words > current.words || (span.words == current.words && span.units > current.units)) best = span
        }
        return best?.let { it.start until it.end } ?: word
    }

    private fun scriptPredicate(language: String): (Int) -> Boolean = when (language) {
        "th" -> { cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.THAI }
        "lo" -> { cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LAO }
        "km" -> { cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.KHMER }
        "my" -> { cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.MYANMAR }
        else -> { cp ->
            val script = Character.UnicodeScript.of(cp)
            script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.BOPOMOFO
        }
    }

    private fun maxUnspacedWord(language: String) = if (language == "zh" || language == "yue") MAX_ZH_WORD else MAX_UNSPACED_WORD

    /** Code point start offsets of the contiguous run of [isScript] characters around [index], clipped to ±[window] code points. */
    private fun unspacedWindow(text: String, index: Int, window: Int, isScript: (Int) -> Boolean): Pair<IntArray, Int>? {
        val tapped = codePointRange(text, index).first
        if (!isScript(text.codePointAt(tapped))) return null
        val starts = ArrayDeque<Int>()
        starts.addLast(tapped)
        var left = tapped
        while (left > 0 && starts.size <= window) {
            val prev = text.offsetByCodePoints(left, -1)
            if (!isScript(text.codePointAt(prev))) break
            starts.addFirst(prev)
            left = prev
        }
        val tappedAt = starts.indexOf(tapped)
        var right = tapped
        var count = 0
        while (count < window) {
            val next = right + Character.charCount(text.codePointAt(right))
            if (next >= text.length || !isScript(text.codePointAt(next))) break
            starts.addLast(next)
            right = next
            count++
        }
        return starts.toIntArray() to tappedAt
    }

    private suspend fun unspacedRange(text: String, index: Int, language: String, parser: LanguageParser): IntRange {
        val max = maxUnspacedWord(language)
        val isScript = scriptPredicate(language)
        val window = unspacedWindow(text, index, max, isScript) ?: return spacedRange(text, index, language, parser)
        val (starts, tapped) = window
        val single = codePointRange(text, index)
        val dicts = dictionaries()
        if (dicts.isEmpty()) return single
        // Every span of 2..max characters that contains the tapped one.
        val ends = IntArray(starts.size) { i ->
            if (i + 1 < starts.size) starts[i + 1] else starts[i] + Character.charCount(text.codePointAt(starts[i]))
        }
        val spans = ArrayList<IntRange>()
        for (from in maxOf(0, tapped - max + 1)..tapped) {
            for (to in maxOf(tapped, from + 1)..minOf(starts.size - 1, from + max - 1)) {
                spans += starts[from] until ends[to]
            }
        }
        if (spans.isEmpty()) return single
        val hits = verify(spans.map { parser.candidates(text.substring(it.first, it.last + 1)) }, dicts)
        var best: IntRange? = null
        for ((i, span) in spans.withIndex()) {
            if (hits[i].isEmpty()) continue
            val current = best
            val length = span.last - span.first
            if (current == null || length > current.last - current.first) best = span
        }
        return best ?: single
    }

    // --- scan ----------------------------------------------------------------------------------

    private class Probe(val start: Int, val end: Int, val candidates: List<LookupCandidate>)

    suspend fun scan(text: String, maxLength: Int, language: String): List<ScannedWord> {
        val dicts = dictionaries()
        if (dicts.isEmpty()) return emptyList()
        val parser = LanguageParsers.forLanguage(language)
        val probes = ArrayList<Probe>()
        val tokens = WordSpans.tokenize(text, joinsApostrophes(language)).take(MAX_SCAN_TOKENS)
        for ((k, token) in tokens.withIndex()) {
            probes += Probe(token.start, token.end, parser.candidates(text.substring(token.start, token.end)))
            for (span in WordSpans.spansStartingAt(text, tokens, k)) {
                probes += Probe(span.start, span.end, expressionCandidates(parser, span.probe))
            }
        }
        if (LanguageParsers.strategy(language) == SegmentStrategy.CJK_LONGEST_MATCH) {
            val isScript = scriptPredicate(language)
            val max = minOf(maxLength, maxUnspacedWord(language))
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val width = Character.charCount(cp)
                if (isScript(cp)) {
                    var end = i
                    var count = 0
                    while (end < text.length && count < max && isScript(text.codePointAt(end))) {
                        end += Character.charCount(text.codePointAt(end))
                        count++
                        probes += Probe(i, end, parser.candidates(text.substring(i, end)))
                    }
                }
                i += width
            }
        }
        if (probes.isEmpty()) return emptyList()
        val hits = verify(probes.map { it.candidates }, dicts)
        data class Found(val probe: Probe, val results: List<TermResult>)
        val found = ArrayList<Found>()
        for ((i, probe) in probes.withIndex()) {
            if (hits[i].isEmpty()) continue
            found += Found(probe, rank(hits[i], bodies = null))
        }
        // Left to right, longest match at each position, no overlaps.
        val words = ArrayList<ScannedWord>()
        var position = 0
        for ((start, atStart) in found.groupBy { it.probe.start }.toSortedMap()) {
            if (start < position) continue
            val longest = atStart.maxByOrNull { it.probe.end - it.probe.start } ?: continue
            words += ScannedWord(
                text = text.substring(start, longest.probe.end),
                start = start,
                end = longest.probe.end,
                results = longest.results,
            )
            position = longest.probe.end
        }
        return words
    }

    // --- lookup --------------------------------------------------------------------------------

    suspend fun lookup(candidates: List<LookupCandidate>): LookupResult {
        val dicts = dictionaries()
        if (dicts.isEmpty() || candidates.isEmpty()) return LookupResult(emptyList())
        val hits = verify(listOf(candidates), dicts).single()
        if (hits.isEmpty()) return LookupResult(emptyList())
        val bodies = queryBodies(hits.map { it.head.id }.distinct())
        return LookupResult(dropAliases(rank(hits, bodies)))
    }

    /**
     * An exact-surface row that merely duplicates the lemma's entry (an MDX redirect imported as a
     * full entry: `belongs` with the glossary of `belong`, BUG-1665 in Fushi) is dropped when the
     * same dictionary's lemma entry was found through a deinflection. Rows with a different
     * glossary are real entries and stay.
     */
    private fun dropAliases(results: List<TermResult>): List<TermResult> {
        val lemmas = results.filter { it.deinflectionReasons.isNotEmpty() }
        if (lemmas.isEmpty()) return results
        return results.filterNot { alias ->
            alias.deinflectionReasons.isEmpty() && alias.glossaryJson.isNotEmpty() && lemmas.any {
                it.dictId == alias.dictId && it.expression != alias.expression && it.glossaryJson == alias.glossaryJson
            }
        }
    }

    /**
     * One result per row (its best candidate), ranked: exact surface > safe normalization >
     * deinflection > irregular form, then single-word before multi-word headwords, then dictionary
     * priority and score. [bodies] null = scan hit (no glossary).
     */
    private fun rank(hits: List<Hit>, bodies: Map<Long, TermBody>?): List<TermResult> {
        val best = LinkedHashMap<Long, Hit>()
        for (hit in hits) {
            val current = best[hit.head.id]
            if (current == null || hit.candidate.kind.rank < current.candidate.kind.rank) best[hit.head.id] = hit
        }
        return best.values
            .map { hit ->
                hit to hit.head.toTermResult(hit.dict, bodies?.get(hit.head.id), hit.candidate.text, hit.candidate.reasons)
            }
            .distinctBy { (_, r) -> "${r.dictId}:${r.expression}:${r.reading}:${r.sequence}:${r.definitionTags}" }
            .sortedWith(
                compareBy<Pair<Hit, TermResult>> { it.first.candidate.kind.rank }
                    .thenBy { if (it.second.expression.any { c -> c == ' ' || c == '-' }) 1 else 0 }
                    .thenBy { it.second.dictionaryPriority }
                    .thenByDescending { it.second.score },
            )
            .map { it.second }
    }

    private companion object {
        const val MAX_ZH_WORD = 10
        const val MAX_UNSPACED_WORD = 16
        const val MAX_SCAN_TOKENS = 120
        const val MAX_EXPRESSION_CANDIDATES = 16
        const val EXPRESSION_CACHE_SIZE = 512
        const val MAX_RULES_CACHE = 64
    }
}
