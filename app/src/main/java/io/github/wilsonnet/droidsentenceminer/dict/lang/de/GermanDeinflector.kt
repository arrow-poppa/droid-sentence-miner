// Ported from Chimahon (GPL-3.0): chimahon/src/main/java/chimahon/dictionary/de/GermanDeinflector.kt
// (arrow-poppa/chimahon, branch agent/byok-ai-v2.4.8-ocr-models @ 4a78d45).
// Adapted for Droid Sentence Miner: package, imports and result type only.
package io.github.wilsonnet.droidsentenceminer.dict.lang.de

import io.github.wilsonnet.droidsentenceminer.dict.lang.DeinflectionResult
import io.github.wilsonnet.droidsentenceminer.dict.lang.Deinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.Rule
import io.github.wilsonnet.droidsentenceminer.dict.lang.RuleDeinflector
import io.github.wilsonnet.droidsentenceminer.dict.lang.prefixInflection
import io.github.wilsonnet.droidsentenceminer.dict.lang.suffixInflection

object GermanDeinflector : Deinflector {

    override fun preProcess(text: String): List<String> = GermanTextPreprocessors.allVariants(text)

    override fun deinflect(
        text: String,
        languageCode: String,
    ): List<DeinflectionResult> {
        return deinflector.deinflect(text)
    }

    private val separablePrefixes = listOf(
        "ab", "an", "auf", "aus", "auseinander", "bei", "da", "dabei", "dar", "daran",
        "dazwischen", "durch", "ein", "empor", "entgegen", "entlang", "entzwei", "fehl",
        "fern", "fest", "fort", "frei", "gegenüber", "gleich", "heim", "her", "herab",
        "heran", "herauf", "heraus", "herbei", "herein", "herüber", "herum", "herunter",
        "hervor", "hin", "hinab", "hinauf", "hinaus", "hinein", "hinterher", "hinunter",
        "hinweg", "hinzu", "hoch", "los", "mit", "nach", "nebenher", "nieder", "statt",
        "um", "vor", "voran", "voraus", "vorbei", "vorüber", "vorweg", "weg", "weiter",
        "wieder", "zu", "zurecht", "zurück", "zusammen",
    )

    private val GERMAN_LETTERS = "a-zA-ZäöüßÄÖÜẞ"

    private val allRules: List<Rule> = buildList {
        // nominalization: -ung -> -en
        add(suffixInflection("ung", "en", emptySet(), setOf("v")))
        add(suffixInflection("lung", "eln", emptySet(), setOf("v")))
        add(suffixInflection("rung", "rn", emptySet(), setOf("v")))

        // -bar: adjective from verb
        add(suffixInflection("bar", "en", setOf("adj"), setOf("v")))
        add(suffixInflection("bar", "n", setOf("adj"), setOf("v")))

        // negative prefix un-
        add(prefixInflection("un", "", emptySet(), setOf("adj")))

        // past participle: ge...t -> ...en/...n
        val basicPastRegex = Regex("^ge([$GERMAN_LETTERS]+)t$")
        for (suffix in listOf("n", "en")) {
            add(Rule.Custom(
                conditionsIn = emptySet(),
                conditionsOut = setOf("vw"),
                isInflected = basicPastRegex,
                deinflectFn = { term -> term.replace(basicPastRegex, "$1$suffix") },
            ))
        }

        // separable past participle: prefix ge...t -> prefix...en/...n
        val prefixDisjunction = separablePrefixes.joinToString("|")
        val separablePastRegex = Regex("^($prefixDisjunction)ge([$GERMAN_LETTERS]+)t$")
        for (suffix in listOf("n", "en")) {
            add(Rule.Custom(
                conditionsIn = emptySet(),
                conditionsOut = setOf("vw"),
                isInflected = separablePastRegex,
                deinflectFn = { term -> term.replace(separablePastRegex, "$1$2$suffix") },
            ))
        }

        // separated prefix: "word ... prefix" -> "word prefix"
        for (prefix in separablePrefixes) {
            val sepRegex = Regex("^([$GERMAN_LETTERS]+) .+ $prefix$")
            add(Rule.Custom(
                conditionsIn = emptySet(),
                conditionsOut = emptySet(),
                isInflected = sepRegex,
                deinflectFn = { term -> term.replace(sepRegex, "$1 $prefix") },
            ))
        }

        // zu-infinitive: prefixzu -> prefix
        for (prefix in separablePrefixes) {
            add(prefixInflection(prefix + "zu", prefix, emptySet(), setOf("v")))
        }

        // -heit/-keit noun suffixes
        add(suffixInflection("heit", "", setOf("n"), setOf("adj", "n")))
        add(suffixInflection("keit", "", setOf("n"), setOf("adj", "n")))
    }

    private val deinflector = RuleDeinflector(allRules)
}
