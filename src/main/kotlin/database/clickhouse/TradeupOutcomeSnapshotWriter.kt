package database.clickhouse

import java.sql.Connection
import java.time.Instant

class TradeupOutcomeSnapshotWriter(
    private val clickHouseClientFactory: ClickHouseClientFactory
) {

    data class OutcomeRow(
        val snapshotAt: Instant,
        val runId: Long,
        val tradeupRecipeId: Long,
        val outcomeItemId: Long,
        val outcomeIndex: Int,
        val outputFloat: Float,
        val outputWearBucketId: Int,
        val outcomeProbability: Float,
        val outcomePrice: Double,
        val expectedContribution: Double,
        val algorithmVersion: String
    )

    fun insertBatch(rows: List<OutcomeRow>) {
        if (rows.isEmpty()) return

        clickHouseClientFactory.query { connection: Connection ->
            val sql = """
                INSERT INTO tradeups.tradeup_outcome_snapshot_raw (
                    snapshot_at, run_id, tradeup_recipe_id, outcome_item_id,
                    outcome_index, output_float, output_wear_bucket_id,
                    outcome_probability, outcome_price, expected_contribution,
                    algorithm_version
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            connection.prepareStatement(sql).use { statement ->
                rows.forEach { row ->
                    statement.setObject(1, row.snapshotAt)
                    statement.setLong(2, row.runId)
                    statement.setLong(3, row.tradeupRecipeId)
                    statement.setLong(4, row.outcomeItemId)
                    statement.setInt(5, row.outcomeIndex)
                    statement.setFloat(6, row.outputFloat)
                    statement.setInt(7, row.outputWearBucketId)
                    statement.setFloat(8, row.outcomeProbability)
                    statement.setDouble(9, row.outcomePrice)
                    statement.setDouble(10, row.expectedContribution)
                    statement.setString(11, row.algorithmVersion)
                    statement.addBatch()
                }

                statement.executeBatch()
            }
        }
    }
}