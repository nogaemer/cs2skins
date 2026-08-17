package de.nogaemer.cs2skinsv2.tradeup.repository

import de.nogaemer.cs2skinsv2.common.dto.PageRequestParams
import de.nogaemer.cs2skinsv2.common.dto.SortSpec
import de.nogaemer.cs2skinsv2.config.ClickHouseClientFactory
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

/**
 * Read-side ClickHouse queries backing GET /tradeups, /tradeups/{id}, and
 * /tradeups/{id}/history. Separate from TradeupSnapshotWriter (write-only) and
 * TradeupOutcomeSnapshotWriter (write-only) -- same read/write split already
 * established for ItemPriceHistoryWriter/ItemPriceHistoryReadRepository in Phase 2.
 *
 * IMPORTANT CONSTRAINT (flagged during implementation): tradeup_snapshot_raw/latest do NOT
 * carry input_rarity_id or allow_stattrak -- TradeupSnapshotWriter.TradeupSnapshotRow never
 * wrote them. Filtering by these would need either a schema migration to denormalize them
 * onto the ClickHouse rows (cheap -- TradeUpOptimizer already knows both values when writing
 * each row), or a Postgres-side pre-filter passing a huge tradeup_recipes.id list into
 * ClickHouse, which doesn't scale. NOT implemented here -- collectionId/minRating/minRoi
 * only, until that migration lands.
 */
@Repository
class TradeUpSnapshotReadRepository(
    private val clickHouseClientFactory: ClickHouseClientFactory
) {

    data class TradeUpSnapshotRow(
        val recipeId: UUID,
        val skin1ItemId: Long,
        val skin2ItemId: Long,
        val skin1Count: Int,
        val skin2Count: Int,
        val skin1WearBucketId: Int,
        val skin2WearBucketId: Int,
        val rating: Float,
        val roi: Float,
        val roiWithDropChange: Float,
        val profitChance: Float,
        val inputCost: Double,
        val inputCostWithDropChange: Double,
        val expectedValue: Double,
        val profitAbs: Double,
        val profitWithDropChange: Double,
        val depthGate: Float,
        val volatilityCombined7d: Float,
        val outcomeCount: Int,
        val algorithmVersion: String,
        val snapshotAt: Instant
    )

    data class SnapshotFilter(
        val skinItemIds: Set<Long>? = null, // resolved from collectionId by the controller, IN-matched against skin_1/skin_2
        val minRating: Double? = null,
        val minRoi: Double? = null
    )

    private val ROW_COLUMNS = """
        tradeup_recipe_id, skin_1_item_id, skin_2_item_id, skin_1_count, skin_2_count,
        skin_1_wear_bucket_id, skin_2_wear_bucket_id, rating, roi, roi_with_drop_change,
        profit_chance, input_cost, input_cost_with_drop_change, expected_value, profit_abs,
        profit_with_drop_change, depth_gate, volatility_combined_7d, outcome_count,
        algorithm_version, snapshot_at
    """.trimIndent()

    private fun buildWhereClause(filter: SnapshotFilter): Pair<String, List<Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()

        filter.skinItemIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            // ClickHouse array-membership check -- one bound param per ID, checked against
            // EITHER input skin (a collection filter should match a recipe that uses that
            // collection's skin as either skin_1 or skin_2, not just skin_1).
            val placeholders = ids.joinToString(",") { "?" }
            clauses.add("(skin_1_item_id IN ($placeholders) OR skin_2_item_id IN ($placeholders))")
            params.addAll(ids)
            params.addAll(ids)
        }
        filter.minRating?.let { clauses.add("rating >= ?"); params.add(it) }
        filter.minRoi?.let { clauses.add("roi_with_drop_change >= ?"); params.add(it) }

        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        return where to params
    }

    fun count(filter: SnapshotFilter): Long {
        val (where, params) = buildWhereClause(filter)
        val sql = "SELECT count() FROM tradeups.tradeup_snapshot_latest $where"
        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }
    }

    fun findPaged(filter: SnapshotFilter, sort: SortSpec, pageParams: PageRequestParams): List<TradeUpSnapshotRow> {
        val (where, params) = buildWhereClause(filter)

        // sortColumn is one of 4 hardcoded literals regardless of input -- SortSpec.parse()
        // already validated sort.field against a whitelist before this is ever called.
        val sortColumn = when (sort.field) {
            "roi" -> "roi_with_drop_change"
            "profitChance" -> "profit_chance"
            "expectedValue" -> "expected_value"
            else -> "rating"
        }
        val sortDirection = if (sort.direction == "desc") "DESC" else "ASC"

        val sql = """
            SELECT $ROW_COLUMNS
            FROM tradeups.tradeup_snapshot_latest
            $where
            ORDER BY $sortColumn $sortDirection
            LIMIT ? OFFSET ?
        """.trimIndent()

        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                params.forEach { statement.setObject(index++, it) }
                statement.setInt(index++, pageParams.size)
                statement.setInt(index, pageParams.page * pageParams.size)
                statement.executeQuery().use { result -> mapRows(result) }
            }
        }
    }

    fun findByRecipeId(recipeId: UUID): TradeUpSnapshotRow? {
        val sql = "SELECT $ROW_COLUMNS FROM tradeups.tradeup_snapshot_latest WHERE tradeup_recipe_id = ?"
        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, recipeId)
                statement.executeQuery().use { result -> mapRows(result).firstOrNull() }
            }
        }
    }

    /** Batch lookup for expanding inputs[]/topOutcome across a page of results in one round trip. */
    fun findByRecipeIds(recipeIds: Collection<UUID>): List<TradeUpSnapshotRow> {
        if (recipeIds.isEmpty()) return emptyList()
        val placeholders = recipeIds.joinToString(",") { "?" }
        val sql = "SELECT $ROW_COLUMNS FROM tradeups.tradeup_snapshot_latest WHERE tradeup_recipe_id IN ($placeholders)"
        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                recipeIds.forEachIndexed { index, id -> statement.setObject(index + 1, id) }
                statement.executeQuery().use { result -> mapRows(result) }
            }
        }
    }

    /**
     * Full run history for one recipe (spec Section 3.5) -- queries tradeup_snapshot_raw,
     * NOT tradeup_snapshot_latest, since raw accumulates one row per (recipe, run) over time
     * and latest only ever shows the newest. Sparse by design -- see spec's documented caveat
     * about a recipe only appearing on runs where it won its group.
     */
    fun findHistory(recipeId: UUID, since: Instant): List<TradeUpSnapshotRow> {
        val sql = """
            SELECT $ROW_COLUMNS
            FROM tradeups.tradeup_snapshot_raw
            WHERE tradeup_recipe_id = ? AND snapshot_at >= ?
            ORDER BY snapshot_at ASC
        """.trimIndent()
        return clickHouseClientFactory.query { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, recipeId)
                statement.setObject(2, since)
                statement.executeQuery().use { result -> mapRows(result) }
            }
        }
    }

    private fun mapRows(result: java.sql.ResultSet): List<TradeUpSnapshotRow> {
        val list = mutableListOf<TradeUpSnapshotRow>()
        while (result.next()) {
            list.add(
                TradeUpSnapshotRow(
                    recipeId = result.getObject("tradeup_recipe_id", UUID::class.java),
                    skin1ItemId = result.getLong("skin_1_item_id"),
                    skin2ItemId = result.getLong("skin_2_item_id"),
                    skin1Count = result.getInt("skin_1_count"),
                    skin2Count = result.getInt("skin_2_count"),
                    skin1WearBucketId = result.getInt("skin_1_wear_bucket_id"),
                    skin2WearBucketId = result.getInt("skin_2_wear_bucket_id"),
                    rating = result.getFloat("rating"),
                    roi = result.getFloat("roi"),
                    roiWithDropChange = result.getFloat("roi_with_drop_change"),
                    profitChance = result.getFloat("profit_chance"),
                    inputCost = result.getDouble("input_cost"),
                    inputCostWithDropChange = result.getDouble("input_cost_with_drop_change"),
                    expectedValue = result.getDouble("expected_value"),
                    profitAbs = result.getDouble("profit_abs"),
                    profitWithDropChange = result.getDouble("profit_with_drop_change"),
                    depthGate = result.getFloat("depth_gate"),
                    volatilityCombined7d = result.getFloat("volatility_combined_7d"),
                    outcomeCount = result.getInt("outcome_count"),
                    algorithmVersion = result.getString("algorithm_version"),
                    snapshotAt = result.getObject("snapshot_at", Instant::class.java)
                )
            )
        }
        return list
    }
}
