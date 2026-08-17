package de.nogaemer.cs2skinsv2.tradeup.repository

import de.nogaemer.cs2skinsv2.config.ClickHouseClientFactory
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Read-side companion to TradeupOutcomeSnapshotWriter (write-only). Queries
 * tradeup_outcome_snapshot_raw for a recipe's possible outcomes, sourced from
 * the LATEST run each recipe appeared in (matching tradeup_snapshot_latest's
 * semantics, since tradeup_outcome_snapshot_raw itself has no ReplacingMergeTree
 * "latest" equivalent -- it's the same accumulate-every-run shape as
 * tradeup_snapshot_raw).
 */
@Repository
class TradeUpOutcomeReadRepository(
    private val clickHouseClientFactory: ClickHouseClientFactory
) {

    data class OutcomeRow(
        val outcomeItemId: Long,
        val outcomeIndex: Int,
        val outputFloat: Float,
        val outputWearBucketId: Int,
        val outcomeProbability: Float,
        val outcomePrice: Double,
        val expectedContribution: Double
    )

    /**
     * All outcomes for a recipe's most recent snapshot. Uses argMax(..., snapshot_at) per
     * outcome_index so a recipe with multiple historical runs only returns its newest
     * outcome set, not one row per (run, outcome) combination.
     */
    fun findLatestOutcomes(recipeId: UUID): List<OutcomeRow> {
        val sql = """
            SELECT
                outcome_index,
                argMax(outcome_item_id, snapshot_at) AS outcome_item_id,
                argMax(output_float, snapshot_at) AS output_float,
                argMax(output_wear_bucket_id, snapshot_at) AS output_wear_bucket_id,
                argMax(outcome_probability, snapshot_at) AS outcome_probability,
                argMax(outcome_price, snapshot_at) AS outcome_price,
                argMax(expected_contribution, snapshot_at) AS expected_contribution
            FROM tradeups.tradeup_outcome_snapshot_raw
            WHERE tradeup_recipe_id = ?
            GROUP BY outcome_index
            ORDER BY outcome_probability DESC
        """.trimIndent()

        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, recipeId)
                statement.executeQuery().use { result ->
                    val list = mutableListOf<OutcomeRow>()
                    while (result.next()) {
                        list.add(
                            OutcomeRow(
                                outcomeItemId = result.getLong("outcome_item_id"),
                                outcomeIndex = result.getInt("outcome_index"),
                                outputFloat = result.getFloat("output_float"),
                                outputWearBucketId = result.getInt("output_wear_bucket_id"),
                                outcomeProbability = result.getFloat("outcome_probability"),
                                outcomePrice = result.getDouble("outcome_price"),
                                expectedContribution = result.getDouble("expected_contribution")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    /** Batch version for expanding topOutcome across a page of list results in ~1-2 round trips. */
    fun findTopOutcomeForRecipes(recipeIds: Collection<UUID>): Map<UUID, OutcomeRow> {
        if (recipeIds.isEmpty()) return emptyMap()
        val placeholders = recipeIds.joinToString(",") { "?" }
        val sql = """
            SELECT
                tradeup_recipe_id,
                argMax(outcome_item_id, outcome_probability) AS outcome_item_id,
                max(outcome_probability) AS outcome_probability
            FROM tradeups.tradeup_outcome_snapshot_raw
            WHERE tradeup_recipe_id IN ($placeholders)
            GROUP BY tradeup_recipe_id
        """.trimIndent()

        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                recipeIds.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
                statement.executeQuery().use { result ->
                    val map = mutableMapOf<UUID, OutcomeRow>()
                    while (result.next()) {
                        val recipeId = result.getObject("tradeup_recipe_id", UUID::class.java)
                        map[recipeId] = OutcomeRow(
                            outcomeItemId = result.getLong("outcome_item_id"),
                            outcomeIndex = 0,
                            outputFloat = 0f,
                            outputWearBucketId = 0,
                            outcomeProbability = result.getFloat("outcome_probability"),
                            outcomePrice = 0.0,
                            expectedContribution = 0.0
                        )
                    }
                    map
                }
            }
        }
    }
}
