package io.github.wilsonnet.droidsentenceminer.dict.lang.english

import io.github.wilsonnet.droidsentenceminer.dict.lang.Rule
import io.github.wilsonnet.droidsentenceminer.dict.lang.suffixInflection

/**
 * Clitic contractions that stay one token on screen (`it's`, `we've`, `they'll`): the base word
 * is a candidate whatever its part of speech (a pronoun is neither noun nor verb), so the rules
 * carry no conditions. Possessive `'s` on nouns is Chimahon's rule; this one adds the
 * unconditioned base so `it's` and `John's` find `it` and `John` in dictionaries with part of
 * speech. The negative `n't` forms are in [EnglishIrregulars] (Fushi's "negative contraction").
 */
internal object EnglishContractions {
    private const val NAME = "contraction"

    val rules: List<Rule> = listOf("'s", "'ve", "'re", "'ll", "'m", "'d").map { clitic ->
        suffixInflection(clitic, "", emptySet(), emptySet(), NAME)
    }
}
