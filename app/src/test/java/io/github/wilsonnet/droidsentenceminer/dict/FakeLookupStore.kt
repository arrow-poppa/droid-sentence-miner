package io.github.wilsonnet.droidsentenceminer.dict

/** In-memory dictionaries for lookup tests: rows are matched by expression or reading, like the SQL. */
internal class FakeLookupStore(
    private val dictionaries: List<DictionaryEntity>,
    private val rows: List<Row>,
) : LookupStore {
    data class Row(
        val dictId: Long,
        val expression: String,
        val rules: String = "",
        val gloss: String = "[\"gloss of $expression\"]",
        val reading: String = "",
        val score: Int = 0,
        val tags: String = "",
    )

    private val withIds = rows.mapIndexed { index, row -> (index + 1).toLong() to row }

    /** Every form the engine asked the database for, in order. */
    val queriedForms = mutableListOf<String>()
    var hasRulesQueries = 0
        private set

    override suspend fun enabledDictionaries(): List<DictionaryEntity> = dictionaries.filter { it.enabled }.sortedBy { it.priority }

    override suspend fun heads(forms: List<String>): List<TermHead> {
        queriedForms += forms
        val wanted = forms.toSet()
        return withIds.filter { (_, row) ->
            dictionaries.any { it.id == row.dictId && it.enabled } &&
                (row.expression in wanted || (row.reading.isNotEmpty() && row.reading in wanted))
        }.map { (id, row) ->
            TermHead(id, row.dictId, row.expression, row.reading, row.tags, row.rules, row.score)
        }
    }

    override suspend fun bodies(ids: List<Long>): List<TermBody> =
        withIds.filter { it.first in ids }.map { (id, row) -> TermBody(id, row.gloss, 0L, "") }

    override suspend fun termMeta(forms: List<String>): List<TermMetaEntity> = emptyList()

    override suspend fun kanji(characters: List<String>): List<KanjiEntity> = emptyList()

    override suspend fun categorizedTags(dictIds: List<Long>): List<TagEntity> = emptyList()

    override suspend fun dictionaryHasRules(dictId: Long): Boolean {
        hasRulesQueries++
        return rows.any { it.dictId == dictId && it.rules.isNotEmpty() }
    }

    override fun observeDictionaryChanges(onChange: () -> Unit) = Unit

    companion object {
        fun dictionary(id: Long = 1, title: String = "dict$id", priority: Int = id.toInt(), termCount: Long = 100) =
            DictionaryEntity(id = id, title = title, revision = "1", priority = priority, termCount = termCount)

        fun single(vararg rows: Row) = FakeLookupStore(listOf(dictionary()), rows.toList())
    }
}
