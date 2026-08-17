package database.postgres

import java.sql.Connection
import java.time.OffsetDateTime
import java.util.*
import javax.sql.DataSource

/**
 * Refreshes best_tradeup_by_skin_pair as a single batch step, called once at
 * the end of TradeUpOptimizer.optimizeAll() (see wiring note below). Not a
 * live-maintained view -- this project is architected around discrete
 * calculator_runs, so "recompute best-by-pair after this run finishes"
 * fits the existing design idiom far better than a continuously-maintained
 * ClickHouse materialized view (which would need one argMaxState(...) per
 * carried-along column and still only gives eventually-consistent results
 * until merges catch up).
 */
class BestTradeUpByPairRepository(
    private val postgresDataSource: DataSource,
    private val clickHouseClientFactory: database.clickhouse.ClickHouseClientFactory
) {

    private data class BestPerPairRow(
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val bestRecipeId: UUID,
        val bestRating: Float,
        val bestRoiWithDropChange: Float,
        val bestProfitChance: Float
    )

    fun refresh() {
        val rows = queryBestPerPairFromClickHouse()
        if (rows.isEmpty()) return
        upsertBatch(rows)
    }

    private fun queryBestPerPairFromClickHouse(): List<BestPerPairRow> {
        val sql = """
            SELECT
                skin_1_item_id,
                skin_2_item_id,
                argMax(tradeup_recipe_id, rating) AS best_recipe_id,
                max(rating) AS best_rating,
                argMax(roi_with_drop_change, rating) AS best_roi_with_drop_change,
                argMax(profit_chance, rating) AS best_profit_chance
            FROM tradeups.tradeup_snapshot_latest
            GROUP BY skin_1_item_id, skin_2_item_id
        """.trimIndent()

        return clickHouseClientFactory.query { connection: Connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val rows = mutableListOf<BestPerPairRow>()
                    while (result.next()) {
                        rows.add(
                            BestPerPairRow(
                                skin1ItemId = result.getLong("skin_1_item_id"),
                                skin2ItemId = result.getLong("skin_2_item_id"),
                                bestRecipeId = result.getObject("best_recipe_id", UUID::class.java),
                                bestRating = result.getFloat("best_rating"),
                                bestRoiWithDropChange = result.getFloat("best_roi_with_drop_change"),
                                bestProfitChance = result.getFloat("best_profit_chance")
                            )
                        )
                    }
                    rows
                }
            }
        }
    }

    private fun upsertBatch(rows: List<BestPerPairRow>) {
        val now = OffsetDateTime.now()
        val sql = """
            INSERT INTO best_tradeup_by_skin_pair (
                skin_1_item_id, skin_2_item_id, best_recipe_id,
                best_rating, best_roi_with_drop_change, best_profit_chance, computed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (skin_1_item_id, skin_2_item_id) DO UPDATE SET
                best_recipe_id = EXCLUDED.best_recipe_id,
                best_rating = EXCLUDED.best_rating,
                best_roi_with_drop_change = EXCLUDED.best_roi_with_drop_change,
                best_profit_chance = EXCLUDED.best_profit_chance,
                computed_at = EXCLUDED.computed_at
        """.trimIndent()

        rows.chunked(2000).forEach { chunk ->
            postgresDataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { statement ->
                    chunk.forEach { row ->
                        // tradeup_recipes CHECK (skin_1_item_id <= skin_2_item_id) --
                        // the ClickHouse rows are already ordered this way by
                        // TradeUpOptimizer, so no swap needed here, but this
                        // guards against that invariant ever drifting.
                        val (skin1, skin2) = if (row.skin1ItemId <= row.skin2ItemId) {
                            row.skin1ItemId to row.skin2ItemId
                        } else {
                            row.skin2ItemId to row.skin1ItemId
                        }
                        statement.setLong(1, skin1)
                        statement.setLong(2, skin2)
                        statement.setObject(3, row.bestRecipeId)
                        statement.setFloat(4, row.bestRating)
                        statement.setFloat(5, row.bestRoiWithDropChange)
                        statement.setFloat(6, row.bestProfitChance)
                        statement.setObject(7, now)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }
}

// -----------------------------------------------------------------------
// Wiring note for TradeUpOptimizer.kt (not a full-file rewrite here, given
// its size -- apply these two changes):
//
// 1. Constructor: add `private val bestTradeUpByPairRepository:
//    BestTradeUpByPairRepository` alongside the existing repositories.
//
// 2. Inside optimize(), where each PendingCandidate becomes a
//    TradeupSnapshotWriter.TradeupSnapshotRow: call
//    RatingCalculator.calculate(tradeUp, inputMetricsA, inputMetricsB,
//    tradeUpInputComponentA.amount, tradeUpInputComponentB.amount,
//    outputMetricsList) and pass its .rating/.depthGate/.volatilityCombined7d
//    into the new TradeupSnapshotRow fields. inputMetricsA/B and
//    outputMetricsList come from CatalogRepository.findCurrentPrice's newly
//    fixed return value (spreadPct/slippagePct/priceImpact*/volatility7d/
//    liquidityScore), mapped into SkinMarketMetrics.
//
// 3. At the very end of optimizeAll() (after runRepository.finishRun
//    succeeds): bestTradeUpByPairRepository.refresh().
// -----------------------------------------------------------------------
