package de.nogaemer.cs2skinsv2.tradeup.repository

import de.nogaemer.cs2skinsv2.config.ClickHouseClientFactory
import org.springframework.stereotype.Repository
import java.math.BigInteger
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
@Repository
class BestTradeUpByPairRepository(
    private val postgresDataSource: DataSource,
    private val clickHouseClientFactory: ClickHouseClientFactory
) {

    data class BestPairRow(
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val bestRecipeId: UUID,
        val bestRating: Float,
        val bestRoiWithDropChange: Float,
        val bestProfitChance: Float,
        val computedAt: OffsetDateTime
    )

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

    private fun BigInteger.toUnsignedLongBits(): Long = this.toLong()

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

        return clickHouseClientFactory.query { connection: java.sql.Connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { result ->
                    val rows = mutableListOf<BestPerPairRow>()
                    while (result.next()) {
                        rows.add(
                            BestPerPairRow(
                                // FIX: getBigDecimal (not getLong) avoids the UInt64
                                // overflow -- .toBigInteger().toUnsignedLongBits()
                                // reinterprets the same bit pattern as a signed Long,
                                // matching how Postgres BIGINT already stores these ids.
                                skin1ItemId = result.getBigDecimal("skin_1_item_id").toBigInteger().toUnsignedLongBits(),
                                skin2ItemId = result.getBigDecimal("skin_2_item_id").toBigInteger().toUnsignedLongBits(),
                                bestRecipeId = result.getObject("best_recipe_id", java.util.UUID::class.java),
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

    /**
     * Point lookup for a specific skin pair. skin1Id may equal skin2Id -- single-skin
     * trade-ups (skin_1_item_id = skin_2_item_id, per the tradeup_recipes CHECK constraint)
     * are valid and must be queryable this way. Order doesn't matter -- normalized the same
     * way the write path already does (skin_1_item_id <= skin_2_item_id).
     */
    fun findBySkinPair(skin1Id: Long, skin2Id: Long): BestPairRow? {
        val (lo, hi) = if (skin1Id <= skin2Id) skin1Id to skin2Id else skin2Id to skin1Id
        val sql = """
        SELECT skin_1_item_id, skin_2_item_id, best_recipe_id, best_rating,
               best_roi_with_drop_change, best_profit_chance, computed_at
        FROM best_tradeup_by_skin_pair
        WHERE skin_1_item_id = ? AND skin_2_item_id = ?
    """.trimIndent()
        return postgresDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setLong(1, lo)
                statement.setLong(2, hi)
                statement.executeQuery().use { result ->
                    if (!result.next()) return@use null
                    mapBestPairRow(result)
                }
            }
        }
    }

    fun findTopN(limit: Int, minRating: Double): List<BestPairRow> {
        val sql = """
        SELECT skin_1_item_id, skin_2_item_id, best_recipe_id, best_rating,
               best_roi_with_drop_change, best_profit_chance, computed_at
        FROM best_tradeup_by_skin_pair
        WHERE best_rating >= ?
        ORDER BY best_rating DESC
        LIMIT ?
    """.trimIndent()
        return postgresDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setDouble(1, minRating)
                statement.setInt(2, limit)
                statement.executeQuery().use { result ->
                    val list = mutableListOf<BestPairRow>()
                    while (result.next()) list.add(mapBestPairRow(result))
                    list
                }
            }
        }
    }

    /**
     * Batch check: which of these recipe IDs are the current #1-rated recipe for their pair.
     * Backs isBestForPair on every row of GET /tradeups (not just when best=true), and backs
     * the best=true filter itself (called with the full candidate ID list, then intersected).
     * One round trip regardless of page size -- never a per-row query.
     */
    fun findBestRecipeIds(recipeIds: Collection<UUID>): Set<UUID> {
        if (recipeIds.isEmpty()) return emptySet()
        val placeholders = recipeIds.joinToString(",") { "?" }
        val sql = "SELECT best_recipe_id FROM best_tradeup_by_skin_pair WHERE best_recipe_id IN ($placeholders)"
        return postgresDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                recipeIds.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
                statement.executeQuery().use { result ->
                    val set = mutableSetOf<UUID>()
                    while (result.next()) set.add(result.getObject("best_recipe_id", UUID::class.java))
                    set
                }
            }
        }
    }

    /**
     * Every best_tradeup_by_skin_pair row whose skin_1/skin_2 falls in this set -- backs
     * best=true combined with a collectionId filter (paginate best_tradeup_by_skin_pair
     * directly, restricted to a collection's item IDs, rather than paginating ClickHouse
     * and hoping enough best-for-pair rows happen to land on this page).
     */
    fun findPagedFiltered(skinItemIds: Set<Long>?, minRating: Double, limit: Int, offset: Int): List<BestPairRow> {
        val clauses = mutableListOf("best_rating >= ?")
        val params = mutableListOf<Any>(minRating)

        skinItemIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            clauses.add("(skin_1_item_id IN ($placeholders) OR skin_2_item_id IN ($placeholders))")
            params.addAll(ids)
            params.addAll(ids)
        }

        val sql = """
        SELECT skin_1_item_id, skin_2_item_id, best_recipe_id, best_rating,
               best_roi_with_drop_change, best_profit_chance, computed_at
        FROM best_tradeup_by_skin_pair
        WHERE ${clauses.joinToString(" AND ")}
        ORDER BY best_rating DESC
        LIMIT ? OFFSET ?
    """.trimIndent()

        return postgresDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                var index = 1
                params.forEach { statement.setObject(index++, it) }
                statement.setInt(index++, limit)
                statement.setInt(index, offset)
                statement.executeQuery().use { result ->
                    val list = mutableListOf<BestPairRow>()
                    while (result.next()) list.add(mapBestPairRow(result))
                    list
                }
            }
        }
    }

    fun countFiltered(skinItemIds: Set<Long>?, minRating: Double): Long {
        val clauses = mutableListOf("best_rating >= ?")
        val params = mutableListOf<Any>(minRating)

        skinItemIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            clauses.add("(skin_1_item_id IN ($placeholders) OR skin_2_item_id IN ($placeholders))")
            params.addAll(ids)
            params.addAll(ids)
        }

        val sql = "SELECT COUNT(*) FROM best_tradeup_by_skin_pair WHERE ${clauses.joinToString(" AND ")}"
        return postgresDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
        }
    }

    private fun mapBestPairRow(result: java.sql.ResultSet): BestPairRow = BestPairRow(
        skin1ItemId = result.getLong("skin_1_item_id"),
        skin2ItemId = result.getLong("skin_2_item_id"),
        bestRecipeId = result.getObject("best_recipe_id", UUID::class.java),
        bestRating = result.getFloat("best_rating"),
        bestRoiWithDropChange = result.getFloat("best_roi_with_drop_change"),
        bestProfitChance = result.getFloat("best_profit_chance"),
        computedAt = result.getObject("computed_at", java.time.OffsetDateTime::class.java)
    )

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
