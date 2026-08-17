package database.postgres

import java.math.BigDecimal
import java.sql.Types
import java.util.*
import javax.sql.DataSource

class TradeUpRecipeOutcomeRepository(
    private val dataSource: DataSource
) {

    data class OutcomeInput(
        val tradeUpRecipeId: UUID,
        val outcomeItemId: Long,
        val theoreticalProbability: Double,
        val sourceCollectionId: Long?
    )

    /**
     * theoretical_probability is a static property of a recipe, independent
     * of price or run time, so this is safe to call repeatedly across runs
     * -- later calls simply refresh the same rows via ON CONFLICT.
     */
    fun upsertOutcomesBatch(outcomes: List<OutcomeInput>) {
        if (outcomes.isEmpty()) return
        val distinctOutcomes = outcomes.distinctBy { it.tradeUpRecipeId to it.outcomeItemId }
        distinctOutcomes.chunked(3000).forEach { chunk -> upsertChunk(chunk) }
    }

    private fun upsertChunk(outcomes: List<OutcomeInput>) {
        val valuesSql = outcomes.joinToString(",") { "(?,?,?,?)" }
        val sql = """
            INSERT INTO tradeup_recipe_outcomes (
                tradeup_recipe_id, outcome_item_id, theoretical_probability, source_collection_id
            )
            VALUES $valuesSql
            ON CONFLICT (tradeup_recipe_id, outcome_item_id)
            DO UPDATE SET
                theoretical_probability = EXCLUDED.theoretical_probability,
                source_collection_id = EXCLUDED.source_collection_id
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                outcomes.forEach { outcome ->
                    statement.setObject(index++, outcome.tradeUpRecipeId)
                    statement.setLong(index++, outcome.outcomeItemId)
                    statement.setBigDecimal(index++, BigDecimal.valueOf(outcome.theoreticalProbability))
                    if (outcome.sourceCollectionId == null) {
                        statement.setNull(index++, Types.BIGINT)
                    } else {
                        statement.setLong(index++, outcome.sourceCollectionId)
                    }
                }
                statement.executeUpdate()
            }
        }
    }
}
