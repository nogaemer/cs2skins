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
     * @param probProfit fraction of outcomes that exceed the input cost (optional)
     * @param variance   population variance of the outcome distribution (optional)
     * @param p05        5th-percentile outcome value (optional)
     * @param p50        50th-percentile (median) outcome value (optional)
     * @param p95        95th-percentile outcome value (optional)
     * @return `true` if a new snapshot was appended; `false` when the snapshot
     *         was skipped because the metrics are identical to the most-recent one.
     */
    fun persistResult(
        tradeupId: Int,
        roi: Double,
        profit: Double,
        inputCost: Double,
        outputCost: Double,
        probProfit: Double? = null,
        variance: Double? = null,
        p05: Double? = null,
        p50: Double? = null,
        p95: Double? = null,
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
                stmt.setLong(6, now.toInstant().toEpochMilli())
                stmt.executeUpdate()
            }

            // 2. Idempotency check: fetch the most-recent snapshot and compare via BigDecimal
            //    to avoid bit-level Double equality issues caused by JDBC wire-protocol
            //    round-trips or future column-type changes.
            val alreadyRecorded = conn.prepareStatement(LATEST_SNAPSHOT_SQL).use { stmt ->
                stmt.setInt(1, tradeupId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@use false
                    approximatelyEqual(rs.getDouble("roi"), roi) &&
                        approximatelyEqual(rs.getDouble("profit"), profit) &&
                        approximatelyEqual(rs.getDouble("input_cost"), inputCost) &&
                        approximatelyEqual(rs.getDouble("output_cost"), outputCost) &&
                        nullableApproximatelyEqual(rs.getNullableDouble("prob_profit"), probProfit) &&
                        nullableApproximatelyEqual(rs.getNullableDouble("variance"), variance) &&
                        nullableApproximatelyEqual(rs.getNullableDouble("p05"), p05) &&
                        nullableApproximatelyEqual(rs.getNullableDouble("p50"), p50) &&
                        nullableApproximatelyEqual(rs.getNullableDouble("p95"), p95)
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
                    if (probProfit != null) stmt.setDouble(7, probProfit) else stmt.setNull(7, java.sql.Types.DOUBLE)
                    if (variance != null) stmt.setDouble(8, variance) else stmt.setNull(8, java.sql.Types.DOUBLE)
                    if (p05 != null) stmt.setDouble(9, p05) else stmt.setNull(9, java.sql.Types.DOUBLE)
                    if (p50 != null) stmt.setDouble(10, p50) else stmt.setNull(10, java.sql.Types.DOUBLE)
                    if (p95 != null) stmt.setDouble(11, p95) else stmt.setNull(11, java.sql.Types.DOUBLE)
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
            INSERT INTO tradeups_current (tradeup_id, roi, profit, input_cost, output_cost, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tradeup_id) DO UPDATE SET
                roi         = EXCLUDED.roi,
                profit      = EXCLUDED.profit,
                input_cost  = EXCLUDED.input_cost,
                output_cost = EXCLUDED.output_cost,
                updated_at  = EXCLUDED.updated_at
        """.trimIndent()

        private val LATEST_SNAPSHOT_SQL = """
            SELECT roi, profit, input_cost, output_cost, prob_profit, variance, p05, p50, p95
            FROM tradeup_snapshots
            WHERE tradeup_id = ?
            ORDER BY snapshot_time DESC
            LIMIT 1
        """.trimIndent()

        private val INSERT_SNAPSHOT_SQL = """
            INSERT INTO tradeup_snapshots (tradeup_id, snapshot_time, roi, profit, input_cost, output_cost, prob_profit, variance, p05, p50, p95)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        /** Tolerance for idempotency comparisons (1e-9 ≈ sub-cent on dollar-scale figures). */
        private const val EPSILON = 1e-9

        /** Returns true when [a] and [b] differ by less than [EPSILON]. */
        private fun approximatelyEqual(a: Double, b: Double) = kotlin.math.abs(a - b) < EPSILON

        /** Returns true when both are null, or both are non-null and within [EPSILON]. */
        private fun nullableApproximatelyEqual(a: Double?, b: Double?): Boolean = when {
            a == null && b == null -> true
            a == null || b == null -> false
            else -> approximatelyEqual(a, b)
        }

        /** Reads a nullable DOUBLE PRECISION column from a ResultSet, returning null for SQL NULL. */
        private fun java.sql.ResultSet.getNullableDouble(columnName: String): Double? =
            getObject(columnName)?.let { (it as Number).toDouble() }
    }
}
