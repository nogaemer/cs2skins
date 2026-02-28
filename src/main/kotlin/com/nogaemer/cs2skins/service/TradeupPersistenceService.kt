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
        outputCost: Double
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

            // 2. Idempotency check: fetch the most-recent snapshot and compare in application
            //    code so we avoid relying on database floating-point equality semantics.
            val alreadyRecorded = conn.prepareStatement(LATEST_SNAPSHOT_SQL).use { stmt ->
                stmt.setInt(1, tradeupId)
                stmt.executeQuery().use { rs ->
                    rs.next() &&
                        rs.getDouble("roi") == roi &&
                        rs.getDouble("profit") == profit &&
                        rs.getDouble("input_cost") == inputCost &&
                        rs.getDouble("output_cost") == outputCost
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
            SELECT roi, profit, input_cost, output_cost
            FROM tradeup_snapshots
            WHERE tradeup_id = ?
            ORDER BY snapshot_time DESC
            LIMIT 1
        """.trimIndent()

        private val INSERT_SNAPSHOT_SQL = """
            INSERT INTO tradeup_snapshots (tradeup_id, snapshot_time, roi, profit, input_cost, output_cost)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
    }
}
