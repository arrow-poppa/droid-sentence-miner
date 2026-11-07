package io.github.wilsonnet.droidsentenceminer.dict.lang

import io.github.wilsonnet.droidsentenceminer.dict.lang.arabic.ArabicDeinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.chinese.ChineseTextPreprocessors
import io.github.wilsonnet.droidsentenceminer.dict.lang.de.GermanDeinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.de.GermanTextPreprocessors
import io.github.wilsonnet.droidsentenceminer.dict.lang.english.EnglishDeinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.english.EnglishIrregulars
import io.github.wilsonnet.droidsentenceminer.dict.lang.es.SpanishDeinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.fr.FrenchDeinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.ko.KoreanDeinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.ko.KoreanTextProcessors
import io.github.wilsonnet.droidsentenceminer.dict.lang.ru.RussianTextPreprocessors
import java.text.Normalizer

/**
 * A text variant that is looked up (and, unless [deinflect] is false, deinflected) in addition to
 * the surface. [reason] names a non-trivial step (`elision`); null = a plain normalization.
 */
internal data class Form(
    val text: String,
    val reason: String? = null,
    val asCandidate: Boolean = true,
    val deinflect: Boolean = true,
)

/**
 * Candidate generation shared by every non-Japanese language: the surface, universal safe
 * normalizations (NFC, case folding, apostrophe and hyphen variants), the language's own
 * variants ([preprocess]) and the deinflections of all of them ([deinflector]).
 *
 * The candidates never move the selection: they are only forms to query; offsets stay on the
 * original text.
 */
internal class RuleBasedParser(
    override val code: String,
    val analyzer: String,
    private val preprocess: (String) -> List<Form> = { emptyList() },
    private val deinflector: Deinflector? = null,
    private val deinflectsExpressions: Boolean = false,
    private val normalizeApostrophes: Boolean = false,
    private val extra: (List<LookupCandidate>) -> List<LookupCandidate> = { emptyList() },
) : LanguageParser {

    override fun candidates(surface: String): List<LookupCandidate> = build(surface, deinflect = true)

    override fun expressionCandidates(span: String): List<LookupCandidate> =
        build(span, deinflect = deinflectsExpressions)

    private fun build(surface: String, deinflect: Boolean): List<LookupCandidate> {
        if (surface.isEmpty()) return emptyList()
        val out = CandidateList()
        out.add(LookupCandidate(surface, emptySet(), emptyList(), CandidateKind.EXACT))

        // Universal normalizations, then the language's variants of each of them.
        val bases = LinkedHashSet<String>()
        bases += surface
        for (text in universalForms(surface, normalizeApostrophes)) {
            bases += text
            out.add(LookupCandidate(text, emptySet(), emptyList(), CandidateKind.NORMALIZED))
        }
        val forms = LinkedHashMap<String, Form>()
        for (base in bases) {
            if (!hasTypographicApostrophe(base)) forms.putIfAbsent(base, Form(base))
            for (form in preprocess(base)) {
                if (form.text.isEmpty() || form.text == base) continue
                forms.putIfAbsent(form.text, form)
                if (form.asCandidate) {
                    val reasons = listOfNotNull(form.reason)
                    val kind = if (reasons.isEmpty()) CandidateKind.NORMALIZED else CandidateKind.DEINFLECTION
                    out.add(LookupCandidate(form.text, emptySet(), reasons, kind))
                }
            }
        }
        if (deinflect && deinflector != null) {
            var budget = MAX_FORMS_TO_DEINFLECT
            for (form in forms.values) {
                if (!form.deinflect || budget-- <= 0) continue
                for (result in deinflector.deinflect(form.text, code)) {
                    if (result.reasons.isEmpty() || result.text.isEmpty()) continue
                    val irregular = result.reasons.any { it.startsWith(Rule.IRREGULAR) }
                    out.add(
                        LookupCandidate(
                            result.text,
                            result.conditionsOut,
                            listOfNotNull(form.reason) + result.reasons,
                            if (irregular) CandidateKind.IRREGULAR else CandidateKind.DEINFLECTION,
                        ),
                    )
                }
            }
            for (candidate in extra(out.toList())) out.add(candidate)
        }
        return out.toList().take(MAX_CANDIDATES)
    }

    /** Insertion-ordered set keyed by (text, conditions, kind-is-exact): the same text via two POS stays twice. */
    private class CandidateList {
        private val map = LinkedHashMap<String, LookupCandidate>()
        fun add(candidate: LookupCandidate) {
            val key = candidate.text + "\u0000" + candidate.conditions.sorted().joinToString(",")
            val existing = map[key]
            if (existing == null || candidate.kind.rank < existing.kind.rank) map[key] = candidate
        }
        fun toList(): List<LookupCandidate> = map.values.sortedBy { it.kind.rank }
    }

    private companion object {
        const val MAX_FORMS_TO_DEINFLECT = 12
        const val MAX_CANDIDATES = 1200
    }
}

/** NFC, lower case, hyphen unification and (languages that use them) apostrophe variants; never includes [surface] itself. */
internal fun universalForms(surface: String, apostrophes: Boolean): List<String> {
    val out = LinkedHashSet<String>()
    val nfc = Normalizer.normalize(surface, Normalizer.Form.NFC)
    val unified = unifyHyphens(nfc)
    val base = listOf(nfc, unified)
    for (b in base) {
        out += b
        out += b.lowercase()
    }
    if (apostrophes) {
        for (b in out.toList()) {
            out += b.replace('’', '\'').replace('ʼ', '\'')
            if (b.contains('\'')) out += b.replace('\'', '’')
        }
    }
    out.remove(surface)
    return out.toList()
}

private fun unifyHyphens(text: String): String = text.replace('‐', '-').replace('‑', '-')

private fun hasTypographicApostrophe(text: String): Boolean = text.contains('’') || text.contains('ʼ')

internal object Parsers {
    fun analyzerName(code: String): String = when (code) {
        "ja" -> "ja-yomitan"
        "en" -> "en-chimahon+fushi"
        "zh", "yue", "th", "lo", "km", "my" -> "cjk-longest-match"
        "ko", "ru", "ar", "de", "es", "fr" -> "$code-chimahon"
        "it" -> "it-chimahon"
        else -> "space-delimited"
    }

    fun create(code: String): LanguageParser = when (code) {
        "ja" -> JapaneseParser()
        "en" -> englishParser()
        "de" -> RuleBasedParser(
            code, analyzerName(code),
            preprocess = { text -> germanVariants(text) },
            deinflector = GermanDeinflector,
            deinflectsExpressions = true,
        )
        "es" -> RuleBasedParser(code, analyzerName(code), deinflector = SpanishDeinflector)
        "fr" -> RuleBasedParser(
            code, analyzerName(code),
            preprocess = { text -> elisionForms(code, text) },
            deinflector = FrenchDeinflector,
            normalizeApostrophes = true,
        )
        "it" -> RuleBasedParser(
            code, analyzerName(code),
            preprocess = { text -> elisionForms(code, text) + diacriticForms(text) },
            normalizeApostrophes = true,
        )
        "pt" -> RuleBasedParser(code, analyzerName(code), normalizeApostrophes = true)
        "ru" -> RuleBasedParser(
            code, analyzerName(code),
            preprocess = { text -> RussianTextPreprocessors.allVariants(text).map { Form(it) } },
        )
        "ar" -> RuleBasedParser(
            code, analyzerName(code),
            preprocess = { text -> ArabicDeinflector.preProcess(text).map { Form(it) } },
            deinflector = ArabicDeinflector,
        )
        "ko" -> RuleBasedParser(
            code, analyzerName(code),
            preprocess = { text ->
                // The deinflector works on jamo: the decomposed form is deinflected, never queried itself.
                val jamo = KoreanTextProcessors.disassemble(text)
                if (jamo == text) emptyList() else listOf(Form(jamo, asCandidate = false))
            },
            deinflector = koreanDeinflector,
        )
        "zh", "yue" -> RuleBasedParser(
            code, analyzerName(code),
            preprocess = { text -> ChineseTextPreprocessors.process(text).map { Form(it) } },
        )
        else -> RuleBasedParser(code, analyzerName(code))
    }

    /** Korean rules are written on jamo (`-ㅂ`): show a neutral reason instead of the raw jamo suffix. */
    private val koreanDeinflector = object : Deinflector {
        override fun deinflect(text: String, languageCode: String): List<DeinflectionResult> =
            KoreanDeinflector.deinflect(text, languageCode).map { result ->
                result.copy(reasons = result.reasons.map { "inflection" })
            }
    }

    private fun germanVariants(text: String): List<Form> =
        GermanTextPreprocessors.allVariants(text).map { Form(it) }

    private fun elisionForms(code: String, text: String): List<Form> {
        val stem = Elision.strip(code, text) ?: return emptyList()
        // `j'aimais` → `aimais`: the stem is looked up and deinflected like a word of its own.
        return listOf(Form(stem, reason = "elision"))
    }

    private fun diacriticForms(text: String): List<Form> =
        listOfNotNull(Elision.foldDiacritics(text)?.let { Form(it) })

    private fun englishParser(): LanguageParser = RuleBasedParser(
        "en", analyzerName("en"),
        deinflector = EnglishDeinflector,
        deinflectsExpressions = true,
        normalizeApostrophes = true,
        extra = ::irregularPhrasalHeads,
    )

    /**
     * Fushi's phrasal-verb head deinflection for irregular verbs (`deinflect_phrasal`): in a
     * multi-word candidate whose second word is a particle/preposition, an irregular first word
     * is replaced by its lemma — `gave up` → `give up`, `given up` → `give up`, and, chained after
     * Chimahon's interposed-object rule, `gave it up` → `gave up` → `give up`.
     */
    private fun irregularPhrasalHeads(existing: List<LookupCandidate>): List<LookupCandidate> {
        val heads = EnglishIrregulars.phrasalHeads
        val out = ArrayList<LookupCandidate>()
        for (candidate in existing) {
            val space = candidate.text.indexOf(' ')
            if (space <= 0 || space >= candidate.text.length - 1) continue
            val head = candidate.text.substring(0, space)
            val tail = candidate.text.substring(space + 1)
            if (tail.substringBefore(' ') !in EnglishDeinflector.phrasalVerbWordSet) continue
            for (lemma in heads[head].orEmpty()) {
                out += LookupCandidate(
                    "$lemma $tail",
                    setOf("v_phr"),
                    candidate.reasons + Rule.IRREGULAR,
                    CandidateKind.IRREGULAR,
                )
            }
        }
        return out
    }
}
