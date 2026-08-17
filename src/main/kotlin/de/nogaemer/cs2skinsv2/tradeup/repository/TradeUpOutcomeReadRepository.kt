package de.nogaemer.cs2skinsv2.tradeup.repository

import de.nogaemer.cs2skinsv2.config.ClickHouseClientFactory
import org.springframework.stereotype.Repository
import java.math.BigInteger
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

    private fun java.sql.ResultSet.getUnsignedLongAsSignedLong(column: String): Long =
        this.getObject(column, BigInteger::class.java).toLong()


    /**
     * All outcomes for a recipe's most recent snapshot. Uses argMax(..., snapshot_at) per
     * outcome_index so a recipe with multiple historical runs only returns its newest
     * outcome set, not one row per (run, outcome) combination.
     */
    fun findLatestOutcomes(recipeId: UUID): List<OutcomeRow> {
        val sql = """
        SELECT
            outcome_index,
            argMax(outcome_item_id, snapshot_at) AS latest_outcome_item_id,
            argMax(output_float, snapshot_at) AS latest_output_float,
            argMax(output_wear_bucket_id, snapshot_at) AS latest_output_wear_bucket_id,
            argMax(outcome_probability, snapshot_at) AS latest_outcome_probability,
            argMax(outcome_price, snapshot_at) AS latest_outcome_price,
            argMax(expected_contribution, snapshot_at) AS latest_expected_contribution
        FROM tradeups.tradeup_outcome_snapshot_raw
        WHERE tradeup_recipe_id = ?
        GROUP BY outcome_index
        ORDER BY latest_outcome_probability DESC
    """.trimIndent()

        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, recipeId)
                statement.executeQuery().use { result ->
                    val list = mutableListOf<OutcomeRow>()
                    while (result.next()) {
                        list.add(
                            OutcomeRow(
                                outcomeItemId = result.getUnsignedLongAsSignedLong("latest_outcome_item_id"),
                                outcomeIndex = result.getInt("outcome_index"),
                                outputFloat = result.getFloat("latest_output_float"),
                                outputWearBucketId = result.getInt("latest_output_wear_bucket_id"),
                                outcomeProbability = result.getFloat("latest_outcome_probability"),
                                outcomePrice = result.getDouble("latest_outcome_price"),
                                expectedContribution = result.getDouble("latest_expected_contribution")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun findTopOutcomeForRecipes(recipeIds: Collection<UUID>): Map<UUID, OutcomeRow> {
        if (recipeIds.isEmpty()) return emptyMap()
        val placeholders = recipeIds.joinToString(",") { "?" }
        val sql = """
        SELECT
            tradeup_recipe_id,
            argMax(outcome_item_id, outcome_probability) AS best_outcome_item_id,
            max(outcome_probability) AS best_outcome_probability
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
                            outcomeItemId = result.getUnsignedLongAsSignedLong("best_outcome_item_id"),
                            outcomeIndex = 0,
                            outputFloat = 0f,
                            outputWearBucketId = 0,
                            outcomeProbability = result.getFloat("best_outcome_probability"),
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
