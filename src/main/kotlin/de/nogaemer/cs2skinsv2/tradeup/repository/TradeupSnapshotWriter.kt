package de.nogaemer.cs2skinsv2.tradeup.repository

import de.nogaemer.cs2skinsv2.config.ClickHouseClientFactory
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.time.Instant
import java.util.*

@Repository
class TradeupSnapshotWriter(private val clickHouseClientFactory: ClickHouseClientFactory) {

    data class TradeupSnapshotRow(
        val snapshotAt: Instant,
        val runId: Long,
        val tradeupRecipeId: UUID,
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val skin1Count: Int,
        val skin2Count: Int,
        val wearBucketId: Int,
        val skin1WearBucketId: Int,
        val skin2WearBucketId: Int,
        val skin1Float: Float,
        val skin2Float: Float,
        val avgInputFloat: Float,
        val avgRawInputFloat: Float,
        val inputCost: Double,
        val inputCostWithDropChange: Double,
        val expectedValue: Double,
        val profitAbs: Double,
        val profitWithDropChange: Double,
        val roi: Float,
        val roiWithDropChange: Float,
        val profitChance: Float,
        val profitPercentage: Float,
        val outcomeCount: Int,
        // New: rating (Part 2 of the DB-rating work).
        val rating: Float,
        val depthGate: Float,
        val volatilityCombined7d: Float,
        val algorithmVersion: String
    )

    fun insertBatch(rows: List<TradeupSnapshotRow>) {
        if (rows.isEmpty()) return
        clickHouseClientFactory.query { connection: Connection ->
            val sql = """
                INSERT INTO tradeups.tradeup_snapshot_raw (
                    snapshot_at, run_id, tradeup_recipe_id, skin_1_item_id, skin_2_item_id,
                    skin_1_count, skin_2_count, wear_bucket_id, skin_1_wear_bucket_id, skin_2_wear_bucket_id,
                    skin_1_float, skin_2_float, average_input_float, average_raw_input_float,
                    input_cost, input_cost_with_drop_change, expected_value, profit_abs, profit_with_drop_change,
                    roi, roi_with_drop_change, profit_chance, profit_percentage, outcome_count,
                    rating, depth_gate, volatility_combined_7d, algorithm_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                rows.forEach { row ->
                    statement.setObject(1, row.snapshotAt)
                    statement.setLong(2, row.runId)
                    statement.setObject(3, row.tradeupRecipeId)
                    statement.setLong(4, row.skin1ItemId)
                    statement.setLong(5, row.skin2ItemId)
                    statement.setInt(6, row.skin1Count)
                    statement.setInt(7, row.skin2Count)
                    statement.setInt(8, row.wearBucketId)
                    statement.setInt(9, row.skin1WearBucketId)
                    statement.setInt(10, row.skin2WearBucketId)
                    statement.setFloat(11, row.skin1Float)
                    statement.setFloat(12, row.skin2Float)
                    statement.setFloat(13, row.avgInputFloat)
                    statement.setFloat(14, row.avgRawInputFloat)
                    statement.setDouble(15, row.inputCost)
                    statement.setDouble(16, row.inputCostWithDropChange)
                    statement.setDouble(17, row.expectedValue)
                    statement.setDouble(18, row.profitAbs)
                    statement.setDouble(19, row.profitWithDropChange)
                    statement.setFloat(20, row.roi)
                    statement.setFloat(21, row.roiWithDropChange)
                    statement.setFloat(22, row.profitChance)
                    statement.setFloat(23, row.profitPercentage)
                    statement.setInt(24, row.outcomeCount)
                    statement.setFloat(25, row.rating)
                    statement.setFloat(26, row.depthGate)
                    statement.setFloat(27, row.volatilityCombined7d)
                    statement.setString(28, row.algorithmVersion)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }
}