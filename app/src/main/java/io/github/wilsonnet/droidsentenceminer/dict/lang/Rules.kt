// Rule engine ported from Chimahon (GPL-3.0):
//   chimahon/src/main/java/chimahon/dictionary/{Deinflector.kt,DeinflectorHelpers.kt}
//   (arrow-poppa/chimahon, branch agent/byok-ai-v2.4.8-ocr-models @ 4a78d45).
// Adapted for Droid Sentence Miner: results keep the rule's output conditions and the names of the
// rules that produced them (Chimahon dropped both), so the lookup can filter by part of speech.
package io.github.wilsonnet.droidsentenceminer.dict.lang

/**
 * One deinflection result: the candidate dictionary form, the part-of-speech conditions it must
 * satisfy ([conditionsOut] of the last rule applied; empty for the unchanged text) and the
 * names of the rules applied to reach it, in application order.
 */
data class DeinflectionResult(
    val text: String,
    val conditionsOut: Set<String> = emptySet(),
    val reasons: List<String> = emptyList(),
)

/**
 * A morphological transformation rule.
 * - [Prefix] strips or replaces a leading segment.
 * - [Suffix] strips or replaces a trailing segment.
 * - [WholeWord] replaces an exact form (irregular forms).
 * - [Sandwich] strips or replaces both ends simultaneously.
 * - [Custom] uses a lambda (German participles, Spanish stem changes, phrasal verbs).
 *
 * [name] labels the rule in [DeinflectionResult.reasons]; empty falls back to [label].
 */
sealed class Rule {
    abstract val conditionsIn: Set<String>
    abstract val conditionsOut: Set<String>
    abstract val isInflected: Regex
    abstract val name: String

    /** What this rule shows as in the lookup's "deinflection" chain. */
    open val label: String get() = name.ifEmpty { "inflection" }

    data class Prefix(
        val inflectedPrefix: String,
        val deinflectedPrefix: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
        override val name: String = "",
    ) : Rule() {
        override val label: String get() = name.ifEmpty { "$inflectedPrefix-" }
    }

    data class Suffix(
        val inflectedSuffix: String,
        val deinflectedSuffix: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
        override val name: String = "",
    ) : Rule() {
        override val label: String get() = name.ifEmpty { "-$inflectedSuffix" }
    }

    data class WholeWord(
        val inflectedWord: String,
        val deinflectedWord: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
        override val name: String = "",
    ) : Rule() {
        override val label: String get() = name.ifEmpty { IRREGULAR }
    }

    data class Sandwich(
        val inflectedPrefix: String,
        val deinflectedPrefix: String,
        val inflectedSuffix: String,
        val deinflectedSuffix: String,
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
        override val name: String = "",
    ) : Rule()

    data class Custom(
        override val conditionsIn: Set<String>,
        override val conditionsOut: Set<String>,
        override val isInflected: Regex,
        val deinflectFn: (String) -> String,
        override val name: String = "",
    ) : Rule()

    companion object {
        /** Label (and reason prefix) of rules that replace a whole irregular form. */
        const val IRREGULAR = "irregular"
    }
}

/** A language's text pre-processing (variants of the surface) and deinflection. */
interface Deinflector {
    /** Pre-process the raw query (normalization, cleaning): the text itself first, then variants. */
    fun preProcess(text: String): List<String> = listOf(text)

    /** Candidate dictionary forms of the (possibly preprocessed) text; includes the text itself. */
    fun deinflect(text: String, languageCode: String): List<DeinflectionResult>
}

fun suffixInflection(
    inflectedSuffix: String,
    deinflectedSuffix: String,
    conditionsIn: Set<String>,
    conditionsOut: Set<String>,
    name: String = "",
): Rule.Suffix = Rule.Suffix(
    inflectedSuffix = inflectedSuffix,
    deinflectedSuffix = deinflectedSuffix,
    conditionsIn = conditionsIn,
    conditionsOut = conditionsOut,
    isInflected = Regex("${Regex.escape(inflectedSuffix)}$"),
    name = name,
)

fun wholeWordInflection(
    inflectedWord: String,
    deinflectedWord: String,
    conditionsIn: Set<String>,
    conditionsOut: Set<String>,
    name: String = "",
): Rule.WholeWord = Rule.WholeWord(
    inflectedWord = inflectedWord,
    deinflectedWord = deinflectedWord,
    conditionsIn = conditionsIn,
    conditionsOut = conditionsOut,
    isInflected = Regex("^${Regex.escape(inflectedWord)}$"),
    name = name,
)

fun prefixInflection(
    inflectedPrefix: String,
    deinflectedPrefix: String,
    conditionsIn: Set<String>,
    conditionsOut: Set<String>,
    name: String = "",
): Rule.Prefix = Rule.Prefix(
    inflectedPrefix = inflectedPrefix,
    deinflectedPrefix = deinflectedPrefix,
    conditionsIn = conditionsIn,
    conditionsOut = conditionsOut,
    isInflected = Regex("^${Regex.escape(inflectedPrefix)}"),
    name = name,
)

fun sandwichInflection(
    inflectedPrefix: String,
    deinflectedPrefix: String,
    inflectedSuffix: String,
    deinflectedSuffix: String,
    conditionsIn: Set<String>,
    conditionsOut: Set<String>,
    name: String = "",
): Rule.Sandwich = Rule.Sandwich(
    inflectedPrefix = inflectedPrefix,
    deinflectedPrefix = deinflectedPrefix,
    inflectedSuffix = inflectedSuffix,
    deinflectedSuffix = deinflectedSuffix,
    conditionsIn = conditionsIn,
    conditionsOut = conditionsOut,
    isInflected = Regex("^${Regex.escape(inflectedPrefix)}.*${Regex.escape(inflectedSuffix)}$"),
    name = name,
)

/**
 * Part-of-speech condition hierarchy shared by the rule engine and the dictionary matching:
 * n ⊃ np/ns (noun, plural, singular), v ⊃ v_phr (verb, phrasal verb).
 */
object ConditionHierarchy {
    private val related = mapOf(
        "n" to setOf("np", "ns"),
        "np" to setOf("n"),
        "ns" to setOf("n"),
        "v" to setOf("v_phr", "vw", "vs"),
        "vw" to setOf("v"),
        "vs" to setOf("v"),
        "v_phr" to setOf("v"),
    )

    /** [conditions] plus every condition they subsume or are subsumed by. */
    fun expand(conditions: Set<String>): Set<String> {
        if (conditions.isEmpty()) return conditions
        val out = LinkedHashSet(conditions)
        for (condition in conditions) related[condition]?.let { out.addAll(it) }
        return out
    }

    /** Whether a candidate that must be one of [candidate] can be a term tagged with [termRules]. */
    fun intersects(candidate: Set<String>, termRules: Set<String>): Boolean {
        if (candidate.isEmpty() || termRules.isEmpty()) return false
        val expanded = expand(candidate)
        return termRules.any { it in expanded }
    }
}

/**
 * Applies [rules] recursively (breadth first, at most [maxResults] results, Yomitan semantics:
 * a rule applies to the original text or when its `conditionsIn` meet the previous rule's
 * `conditionsOut`). Results include the unchanged text first.
 */
class RuleDeinflector(
    rules: List<Rule>,
    private val maxResults: Int = MAX_DEINFLECTION_RESULTS,
) {
    private val indexedRules = IndexedRules(rules)

    private class Entry(val text: String, val conditions: Set<String>, val reasons: List<String>)

    fun deinflect(text: String): List<DeinflectionResult> {
        val results = mutableListOf(Entry(text, emptySet(), emptyList()))
        val seen = hashSetOf(resultKey(text, emptySet()))
        var i = 0
        while (i < results.size && results.size < maxResults) {
            val current = results[i]
            for (rule in indexedRules.forText(current.text, current.conditions)) {
                val deinflected = applyRule(current.text, rule) ?: continue
                if (deinflected.isEmpty()) continue
                if (!seen.add(resultKey(deinflected, rule.conditionsOut))) continue
                results += Entry(deinflected, rule.conditionsOut, current.reasons + rule.label)
                if (results.size >= maxResults) break
            }
            i++
        }
        return results.map { DeinflectionResult(it.text, it.conditions, it.reasons) }
    }

    private companion object {
        const val MAX_DEINFLECTION_RESULTS = 200
    }
}

private fun applyRule(text: String, rule: Rule): String? = when (rule) {
    is Rule.Prefix -> {
        if (!text.startsWith(rule.inflectedPrefix)) {
            null
        } else {
            rule.deinflectedPrefix + text.drop(rule.inflectedPrefix.length)
        }
    }
    is Rule.Suffix -> {
        if (!text.endsWith(rule.inflectedSuffix)) {
            null
        } else {
            text.dropLast(rule.inflectedSuffix.length) + rule.deinflectedSuffix
        }
    }
    is Rule.WholeWord -> if (text != rule.inflectedWord) null else rule.deinflectedWord
    is Rule.Sandwich -> {
        if (!text.startsWith(rule.inflectedPrefix) || !text.endsWith(rule.inflectedSuffix)) {
            null
        } else if (text.length < rule.inflectedPrefix.length + rule.inflectedSuffix.length) {
            null
        } else {
            rule.deinflectedPrefix +
                text.substring(rule.inflectedPrefix.length, text.length - rule.inflectedSuffix.length) +
                rule.deinflectedSuffix
        }
    }
    is Rule.Custom -> if (!rule.isInflected.containsMatchIn(text)) null else rule.deinflectFn(text)
}

private fun resultKey(text: String, conditions: Set<String>): String =
    text + "\u0000" + conditions.sorted().joinToString("\u0001")

private class IndexedRules(rules: List<Rule>) {
    private val suffixRules = RuleTrie<Rule.Suffix>(reversed = true)
    private val prefixRules = RuleTrie<Rule>(reversed = false)
    private val wholeWordRules = ExactRuleIndex<Rule.WholeWord>()
    private val customRules: ConditionRuleIndex<Rule.Custom>

    init {
        val custom = mutableListOf<OrderedRule<Rule.Custom>>()
        for ((index, rule) in rules.withIndex()) {
            when (rule) {
                is Rule.Suffix -> suffixRules.add(rule.inflectedSuffix, OrderedRule(index, rule))
                is Rule.Prefix -> prefixRules.add(rule.inflectedPrefix, OrderedRule(index, rule))
                is Rule.Sandwich -> prefixRules.add(rule.inflectedPrefix, OrderedRule(index, rule))
                is Rule.WholeWord -> wholeWordRules.add(rule.inflectedWord, OrderedRule(index, rule))
                is Rule.Custom -> custom += OrderedRule(index, rule)
            }
        }
        customRules = ConditionRuleIndex(custom)
    }

    fun forText(text: String, conditions: Set<String>): Sequence<Rule> {
        return (
            suffixRules.match(text, conditions) +
                prefixRules.match(text, conditions) +
                wholeWordRules.match(text, conditions) +
                customRules.forConditions(conditions)
            )
            .sortedBy { it.index }
            .map { it.rule }
    }
}

private data class OrderedRule<out T : Rule>(val index: Int, val rule: T)

private class RuleTrie<T : Rule>(private val reversed: Boolean) {
    private val root = Node<T>()

    fun add(text: String, rule: OrderedRule<T>) {
        var node = root
        val indices = if (reversed) text.lastIndex downTo 0 else text.indices
        for (i in indices) {
            node = node.children.getOrPut(text[i]) { Node() }
        }
        node.rules += rule
    }

    fun match(text: String, conditions: Set<String>): Sequence<OrderedRule<T>> = sequence {
        var node = root
        yieldAll(node.ruleIndex.forConditions(conditions))
        val indices = if (reversed) text.lastIndex downTo 0 else text.indices
        for (i in indices) {
            node = node.children[text[i]] ?: break
            yieldAll(node.ruleIndex.forConditions(conditions))
        }
    }

    private class Node<T : Rule> {
        val children = mutableMapOf<Char, Node<T>>()
        val rules: MutableList<OrderedRule<T>> = mutableListOf()
        val ruleIndex: ConditionRuleIndex<T> by lazy { ConditionRuleIndex(rules) }
    }
}

private class ExactRuleIndex<T : Rule> {
    private val mutableRulesByText = mutableMapOf<String, MutableList<OrderedRule<T>>>()
    private val indexesByText: Map<String, ConditionRuleIndex<T>> by lazy {
        mutableRulesByText.mapValues { (_, rules) -> ConditionRuleIndex(rules) }
    }

    fun add(text: String, rule: OrderedRule<T>) {
        mutableRulesByText.getOrPut(text) { mutableListOf() } += rule
    }

    fun match(text: String, conditions: Set<String>): Sequence<OrderedRule<T>> {
        return indexesByText[text]?.forConditions(conditions) ?: emptySequence()
    }
}

private class ConditionRuleIndex<T : Rule>(private val rules: List<OrderedRule<T>>) {
    private val rulesByCondition: Map<String, List<OrderedRule<T>>> = run {
        val mutable = mutableMapOf<String, MutableList<OrderedRule<T>>>()
        for (orderedRule in rules) {
            for (condition in orderedRule.rule.conditionsIn) {
                mutable.getOrPut(condition) { mutableListOf() } += orderedRule
            }
        }
        mutable
    }

    fun forConditions(conditions: Set<String>): Sequence<OrderedRule<T>> {
        if (conditions.isEmpty()) return rules.asSequence()
        return ConditionHierarchy.expand(conditions)
            .asSequence()
            .flatMap { rulesByCondition[it].orEmpty().asSequence() }
            .distinctBy { it.index }
    }
}
