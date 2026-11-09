package io.github.wilsonnet.droidsentenceminer.dict.lang

import io.github.wilsonnet.droidsentenceminer.dict.DictionaryEntity
import io.github.wilsonnet.droidsentenceminer.dict.TermHead

/** Whether a candidate may be the headword of a dictionary row (Yomitan `rules` against the candidate's conditions). */
internal object RuleMatching {
    /** A Yomitan term's `rules` string (space or comma separated: `v`, `n`, `adj`, `v_phr`, …) as a set. */
    fun parseRules(rules: String?): Set<String> {
        if (rules.isNullOrBlank()) return emptySet()
        return rules.split(' ', ',', '\t').filter { it.isNotEmpty() }.toSet()
    }

    /**
     * - the surface and its normalizations (no reasons) and candidates without conditions always match;
     * - a deinflected candidate matches a row whose rules intersect its conditions (n ⊃ np/ns, v ⊃ v_phr);
     * - a row without rules matches a deinflected candidate only when its dictionary has no rules
     *   anywhere (a part-of-speech-less dictionary, BUG-2543): there `rules` carries no information
     *   and refusing would turn every inflection into a miss. In a dictionary with rules an empty
     *   `rules` means "does not inflect", so a noun is never the lemma of a verb form.
     *
     * [dictionaryHasRules] is only consulted for the last case.
     */
    suspend fun accepts(
        candidate: LookupCandidate,
        row: TermHead,
        dict: DictionaryEntity,
        dictionaryHasRules: suspend (DictionaryEntity) -> Boolean,
    ): Boolean {
        if (candidate.reasons.isEmpty() || candidate.conditions.isEmpty()) return true
        val rules = parseRules(row.rules)
        if (rules.isNotEmpty()) return ConditionHierarchy.intersects(candidate.conditions, rules)
        return !dictionaryHasRules(dict)
    }
}
