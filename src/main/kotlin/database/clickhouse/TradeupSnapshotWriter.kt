package database.clickhouse

import java.sql.Connection
import java.time.Instant

class TradeupSnapshotWriter(
    private val clickHouseClientFactory: ClickHouseClientFactory
) {

    data class TradeupSnapshotRow(
        val snapshotAt: Instant,
        val runId: Long,
        val tradeupRecipeId: Long,
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val skin1Count: Int,
        val skin2Count: Int,
        val wearBucketId: Int,
        val skin1WearBucketId: Int,   // NEW -- lets analytics join straight to
        val skin2WearBucketId: Int,   // item_current_prices without recomputing CSWear
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
        val algorithmVersion: String
    )

    fun insertBatch(rows: List<TradeupSnapshotRow>) {
        if (rows.isEmpty()) return

        clickHouseClientFactory.query { connection: Connection ->
            val sql = """
                INSERT INTO tradeups.tradeup_snapshot_raw (
                    snapshot_at, run_id, tradeup_recipe_id,
                    skin_1_item_id, skin_2_item_id, skin_1_count, skin_2_count,
                    wear_bucket_id, skin_1_wear_bucket_id, skin_2_wear_bucket_id,
                    skin_1_float, skin_2_float,
                    average_input_float, average_raw_input_float,
                    input_cost, input_cost_with_drop_change,
                    expected_value, profit_abs, profit_with_drop_change,
                    roi, roi_with_drop_change, profit_chance, profit_percentage,
                    outcome_count, algorithm_version
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
            """.trimIndent()

            connection.prepareStatement(sql).use { statement ->
                rows.forEach { row ->
                    statement.setObject(1, row.snapshotAt)
                    statement.setLong(2, row.runId)
                    statement.setLong(3, row.tradeupRecipeId)
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
                    statement.setString(25, row.algorithmVersion)
                    statement.addBatch()
                }

                statement.executeBatch()
            }
        }
    }
}
