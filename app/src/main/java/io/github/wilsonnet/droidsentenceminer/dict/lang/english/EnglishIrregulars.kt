// Irregular English forms (went→go, children→child, mice→mouse, better→good, …).
// Data from Fushi (GPL-3.0): fushi/assets/transforms/en_irregular.json
// (arrow-poppa/Fushi, branch agent/claude-ai-explanation-v3.0.0-ocr-detector @ ac11600), 8 groups / 399 rules,
// embedded as string tables so JVM unit tests need no Android assets. Format: `from>to` replaces the whole
// word, `~from>to` replaces a word ending (used on compounds: -men, -children, -mice, …).
package io.github.wilsonnet.droidsentenceminer.dict.lang.english

import io.github.wilsonnet.droidsentenceminer.dict.lang.Rule
import io.github.wilsonnet.droidsentenceminer.dict.lang.suffixInflection
import io.github.wilsonnet.droidsentenceminer.dict.lang.wholeWordInflection

internal object EnglishIrregulars {
    /** One group of forms: its rule name, whether verb heads of phrasal verbs use it, conditions and the table. */
    class Group(val name: String, val phrasalVerb: Boolean, val conditionsIn: String, val conditionsOut: String, val table: String)

    val groups: List<Group> = listOf(
        Group(
            "irregular past", true, "v", "v",
            "arose>arise ate>eat awoke>awake bade>bid became>become befell>befall began>begin begot>beget " +
                "bit>bite blew>blow bore>bear broke>break came>come chose>choose could>can did>do dove>dive " +
                "drank>drink drew>draw drove>drive fell>fall flew>fly forbade>forbid forbore>forbear " +
                "foresaw>foresee forgave>forgive forgot>forget forsook>forsake forswore>forswear forwent>forgo " +
                "froze>freeze gave>give got>get grew>grow hid>hide knew>know lay>lie might>may misgave>misgive " +
                "mistook>mistake outdid>outdo outgrew>outgrow overate>overeat overcame>overcome overdid>overdo " +
                "overran>overrun overrode>override oversaw>oversee overthrew>overthrow overtook>overtake ran>run " +
                "rang>ring redid>redo redrew>redraw reran>rerun retook>retake rewrote>rewrite rode>ride rose>rise " +
                "sang>sing sank>sink saw>see shone>shine shook>shake should>shall shrank>shrink slew>slay " +
                "slid>slide smote>smite spoke>speak sprang>spring stank>stink stole>steal strode>stride " +
                "strove>strive swam>swim swore>swear threw>throw took>take tore>tear trod>tread underlay>underlie " +
                "undertook>undertake underwent>undergo underwrote>underwrite undid>undo was>be went>go were>be " +
                "withdrew>withdraw woke>wake wore>wear would>will wove>weave wrote>write",
        ),
        Group(
            "irregular past participle", true, "v", "v",
            "arisen>arise awoken>awake beaten>beat been>be befallen>befall begotten>beget begun>begin " +
                "bidden>bid bitten>bite blown>blow born>bear borne>bear broken>break chosen>choose done>do " +
                "drawn>draw driven>drive drunk>drink eaten>eat fallen>fall flown>fly forbidden>forbid " +
                "forborne>forbear foreseen>foresee forgiven>forgive forgone>forgo forgotten>forget " +
                "forsaken>forsake forsworn>forswear frozen>freeze given>give gone>go gotten>get grown>grow " +
                "hewn>hew hidden>hide known>know lain>lie mistaken>mistake mown>mow outdone>outdo " +
                "outgrown>outgrow overdone>overdo overeaten>overeat overridden>override overseen>oversee " +
                "overtaken>overtake overthrown>overthrow proven>prove redone>redo redrawn>redraw retaken>retake " +
                "rewritten>rewrite ridden>ride risen>rise rung>ring seen>see sewn>sew shaken>shake shorn>shear " +
                "shown>show shrunk>shrink slain>slay smitten>smite sown>sow spoken>speak sprung>spring " +
                "stolen>steal strewn>strew stricken>strike stridden>stride striven>strive stunk>stink sung>sing " +
                "sunk>sink swollen>swell sworn>swear swum>swim taken>take thrown>throw torn>tear trodden>tread " +
                "undergone>undergo underlain>underlie undertaken>undertake underwritten>underwrite undone>undo " +
                "withdrawn>withdraw woken>wake worn>wear woven>weave written>write",
        ),
        Group(
            "irregular past/participle", true, "v", "v",
            "beheld>behold bent>bend besought>beseech bled>bleed bought>buy bound>bind bred>breed " +
                "brought>bring built>build burnt>burn caught>catch clad>clothe clung>cling crept>creep dealt>deal " +
                "dreamt>dream dug>dig fed>feed felt>feel fled>flee flung>fling foretold>foretell fought>fight " +
                "found>find ground>grind had>have heard>hear held>hold hung>hang kept>keep knelt>kneel leant>lean " +
                "leapt>leap learnt>learn led>lead left>leave lent>lend lit>light lost>lose made>make meant>mean " +
                "met>meet misheard>mishear misled>mislead misspelt>misspell misunderstood>misunderstand " +
                "overfed>overfeed overheard>overhear overhung>overhang overshot>overshoot pled>plead " +
                "rebuilt>rebuild relit>relight remade>remake resent>resend resold>resell restrung>restring " +
                "rethought>rethink retold>retell rewound>rewind sat>sit sent>send shod>shoe shot>shoot " +
                "slept>sleep slung>sling smelt>smell snuck>sneak sold>sell sought>seek spat>spit sped>speed " +
                "spelt>spell spent>spend spilt>spill spoilt>spoil spun>spin stood>stand struck>strike " +
                "strung>string stuck>stick stung>sting swept>sweep swung>swing taught>teach thought>think " +
                "told>tell unbound>unbind understood>understand unwound>unwind upheld>uphold wept>weep " +
                "withheld>withhold withstood>withstand won>win wound>wind wrung>wring",
        ),
        Group(
            "irregular present", true, "v", "v",
            "am>be are>be doth>do has>have hath>have is>be",
        ),
        Group(
            "negative contraction", false, "v", "v",
            "~n't> ain't>be can't>can shan't>shall won't>will",
        ),
        Group(
            "irregular plural", false, "np", "ns",
            "addenda>addendum algae>alga alumnae>alumna alumni>alumnus amoebae>amoeba analyses>analysis " +
                "antennae>antenna apices>apex appendices>appendix automata>automaton axes>axis bacteria>bacterium " +
                "bases>basis brethren>brother cacti>cactus cherubim>cherub codices>codex corpora>corpus " +
                "crises>crisis criteria>criterion curricula>curriculum data>datum diagnoses>diagnosis dice>die " +
                "dogmata>dogma emphases>emphasis errata>erratum foci>focus formulae>formula fungi>fungus " +
                "genera>genus helices>helix hippopotami>hippopotamus hypotheses>hypothesis indices>index " +
                "larvae>larva loci>locus matrices>matrix media>medium memoranda>memorandum millennia>millennium " +
                "nebulae>nebula neuroses>neurosis nuclei>nucleus oases>oasis octopi>octopus oxen>ox " +
                "parentheses>parenthesis pence>penny people>person phenomena>phenomenon prognoses>prognosis " +
                "psychoses>psychosis radii>radius schemata>schema seraphim>seraph stigmata>stigma " +
                "stimuli>stimulus strata>stratum syllabi>syllabus synopses>synopsis theses>thesis " +
                "vertebrae>vertebra vertices>vertex vortices>vortex ~children>child ~feet>foot ~geese>goose " +
                "~lice>louse ~men>man ~mice>mouse ~teeth>tooth",
        ),
        Group(
            "irregular comparative", false, "adj", "adj",
            "better>good better>well worse>bad worse>ill more>many more>much less>little further>far " +
                "farther>far elder>old",
        ),
        Group(
            "irregular comparative", false, "adv", "adv",
            "better>well worse>badly more>much less>little further>far farther>far",
        ),
        Group(
            "irregular superlative", false, "adj", "adj",
            "best>good best>well worst>bad worst>ill most>many most>much least>little furthest>far " +
                "farthest>far eldest>old",
        ),
        Group(
            "irregular superlative", false, "adv", "adv",
            "best>well worst>badly most>much least>little furthest>far farthest>far",
        ),
    )

    private fun Group.entries(): List<Pair<String, String>> =
        table.split(' ').filter { it.isNotEmpty() }.map { token ->
            val (from, to) = token.split('>', limit = 2)
            from to to
        }

    /** Every group as engine rules, in the order of the Fushi table. */
    val rules: List<Rule> by lazy {
        buildList {
            for (group in groups) {
                val rin = group.conditionsIn.split(',').toSet()
                val rout = group.conditionsOut.split(',').toSet()
                for ((from, to) in group.entries()) {
                    if (from.startsWith('~')) {
                        add(suffixInflection(from.substring(1), to, rin, rout, group.name))
                    } else {
                        add(wholeWordInflection(from, to, rin, rout, group.name))
                    }
                }
            }
        }
    }

    /** Irregular verb forms that can head a phrasal verb: `gave` -> `give` (past, participle, present groups). */
    val phrasalHeads: Map<String, List<String>> by lazy {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (group in groups.filter { it.phrasalVerb }) {
            for ((from, to) in group.entries()) {
                if (from.startsWith('~')) continue
                val list = map.getOrPut(from) { mutableListOf() }
                if (to !in list) list += to
            }
        }
        map
    }
}
