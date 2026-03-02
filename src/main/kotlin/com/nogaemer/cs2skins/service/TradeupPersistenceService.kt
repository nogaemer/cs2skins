package com.nogaemer.cs2skins.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persists a single computed trade-up result atomically by:
 *  1. Upserting [tradeups_current] with the latest metrics.
 *  2. Appending a new row to [tradeup_snapshots] in the **same** JDBC transaction.
 *
 * Idempotency guarantee: a retry that sends **identical metrics** for the same
 * [tradeupId] will not produce a duplicate snapshot row.  A different metric set
 * (i.e. prices have changed since the last run) will always produce a new snapshot.
 */
@Service
class TradeupPersistenceService(private val jdbcTemplate: JdbcTemplate) {

    /**
     * @return `true` if a new snapshot was appended; `false` when the snapshot
     *         was skipped because the metrics are identical to the most-recent one.
     */
    fun persistResult(
        tradeupId: Int,
        roi: Double,
        profit: Double,
        inputCost: Double,
        outputCost: Double,
        inputCostNoDropChange: Double,
        profitNoDropChange: Double,
        roiNoDropChange: Double,
        profitChance: Double
    ): Boolean = jdbcTemplate.execute { conn: Connection ->
        val wasAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val now = OffsetDateTime.now(ZoneOffset.UTC)

            // 1. Upsert tradeups_current
            conn.prepareStatement(UPSERT_CURRENT_SQL).use { stmt ->
                stmt.setInt(1, tradeupId)
                stmt.setDouble(2, roi)
                stmt.setDouble(3, profit)
                stmt.setDouble(4, inputCost)
                stmt.setDouble(5, outputCost)
                stmt.setDouble(6, inputCostNoDropChange)
                stmt.setDouble(7, profitNoDropChange)
                stmt.setDouble(8, roiNoDropChange)
                stmt.setDouble(9, profitChance)
                stmt.setLong(10, now.toInstant().toEpochMilli())
                stmt.executeUpdate()
            }

            // 2. Idempotency check: fetch the most-recent snapshot and compare via BigDecimal
            //    to avoid bit-level Double equality issues caused by JDBC wire-protocol
            //    round-trips or future column-type changes.
            val alreadyRecorded = conn.prepareStatement(LATEST_SNAPSHOT_SQL).use { stmt ->
                stmt.setInt(1, tradeupId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@use false
                    // Non-nullable columns
                    if (!approximatelyEqual(rs.getDouble("roi"), roi)) return@use false
                    if (!approximatelyEqual(rs.getDouble("profit"), profit)) return@use false
                    if (!approximatelyEqual(rs.getDouble("input_cost"), inputCost)) return@use false
                    if (!approximatelyEqual(rs.getDouble("output_cost"), outputCost)) return@use false
                    // Nullable columns: treat NULL in DB as not-equal to any Double value
                    val dbInputCostNdc = rs.getDouble("input_cost_no_drop_change")
                    if (rs.wasNull() || !approximatelyEqual(dbInputCostNdc, inputCostNoDropChange)) return@use false
                    val dbProfitNdc = rs.getDouble("profit_no_drop_change")
                    if (rs.wasNull() || !approximatelyEqual(dbProfitNdc, profitNoDropChange)) return@use false
                    val dbRoiNdc = rs.getDouble("roi_no_drop_change")
                    if (rs.wasNull() || !approximatelyEqual(dbRoiNdc, roiNoDropChange)) return@use false
                    val dbProfitChance = rs.getDouble("profit_chance")
                    if (rs.wasNull() || !approximatelyEqual(dbProfitChance, profitChance)) return@use false
                    true
                }
            }

            val snapshotWritten: Boolean
            if (!alreadyRecorded) {
                // 3. Append snapshot
                conn.prepareStatement(INSERT_SNAPSHOT_SQL).use { stmt ->
                    stmt.setInt(1, tradeupId)
                    stmt.setTimestamp(2, Timestamp.from(now.toInstant()))
                    stmt.setDouble(3, roi)
                    stmt.setDouble(4, profit)
                    stmt.setDouble(5, inputCost)
                    stmt.setDouble(6, outputCost)
                    stmt.setDouble(7, inputCostNoDropChange)
                    stmt.setDouble(8, profitNoDropChange)
                    stmt.setDouble(9, roiNoDropChange)
                    stmt.setDouble(10, profitChance)
                    stmt.executeUpdate()
                }
                snapshotWritten = true
            } else {
                snapshotWritten = false
            }

            conn.commit()
            snapshotWritten
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = wasAutoCommit
        }
    } ?: false

    companion object {
        private val UPSERT_CURRENT_SQL = """
            INSERT INTO tradeups_current (tradeup_id, roi, profit, input_cost, output_cost,
                input_cost_no_drop_change, profit_no_drop_change, roi_no_drop_change, profit_chance,
                updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tradeup_id) DO UPDATE SET
                roi                       = EXCLUDED.roi,
                profit                    = EXCLUDED.profit,
                input_cost                = EXCLUDED.input_cost,
                output_cost               = EXCLUDED.output_cost,
                input_cost_no_drop_change = EXCLUDED.input_cost_no_drop_change,
                profit_no_drop_change     = EXCLUDED.profit_no_drop_change,
                roi_no_drop_change        = EXCLUDED.roi_no_drop_change,
                profit_chance             = EXCLUDED.profit_chance,
                updated_at                = EXCLUDED.updated_at
        """.trimIndent()

        private val LATEST_SNAPSHOT_SQL = """
            SELECT roi, profit, input_cost, output_cost,
                   input_cost_no_drop_change, profit_no_drop_change, roi_no_drop_change, profit_chance
            FROM tradeup_snapshots
            WHERE tradeup_id = ?
            ORDER BY snapshot_time DESC
            LIMIT 1
        """.trimIndent()

        private val INSERT_SNAPSHOT_SQL = """
            INSERT INTO tradeup_snapshots (tradeup_id, snapshot_time, roi, profit, input_cost, output_cost,
                input_cost_no_drop_change, profit_no_drop_change, roi_no_drop_change, profit_chance)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        /** Tolerance for idempotency comparisons (1e-9 ≈ sub-cent on dollar-scale figures). */
        private const val EPSILON = 1e-9

        /** Returns true when [a] and [b] differ by less than [EPSILON]. */
        private fun approximatelyEqual(a: Double, b: Double) = kotlin.math.abs(a - b) < EPSILON
    }
}
