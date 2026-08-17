package de.nogaemer.cs2skinsv2.tradeup.repository

import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import javax.sql.DataSource

@Repository
class CalculatorRunRepository(
    private val dataSource: DataSource
) {

    data class RunRow(
        val id: Long,
        val startedAt: OffsetDateTime,
        val finishedAt: OffsetDateTime?,
        val snapshotAt: OffsetDateTime,
        val intervalLabel: String,
        val calculatorVersion: String,
        val status: String,
        val rowCount: Long?,
        val errorMessage: String?
    )

    fun findById(runId: Long): RunRow? {
        val sql = """
        SELECT id, started_at, finished_at, snapshot_at, interval_label,
               calculator_version, status, row_count, error_message
        FROM calculator_runs
        WHERE id = ?
    """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, runId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapRunRow(rs) else null }
            }
        }
    }

    fun findRunningRun(): RunRow? {
        val sql = """
        SELECT id, started_at, finished_at, snapshot_at, interval_label,
               calculator_version, status, row_count, error_message
        FROM calculator_runs
        WHERE status = 'RUNNING'
        ORDER BY started_at DESC
        LIMIT 1
    """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs -> if (rs.next()) mapRunRow(rs) else null }
            }
        }
    }

    fun findRecent(limit: Int): List<RunRow> {
        val sql = """
        SELECT id, started_at, finished_at, snapshot_at, interval_label,
               calculator_version, status, row_count, error_message
        FROM calculator_runs
        ORDER BY started_at DESC
        LIMIT ?
    """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<RunRow>()
                    while (rs.next()) rows.add(mapRunRow(rs))
                    rows
                }
            }
        }
    }

    private fun mapRunRow(rs: ResultSet): RunRow {
        val rowCount = rs.getLong("row_count")
        return RunRow(
            id = rs.getLong("id"),
            startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
            finishedAt = rs.getObject("finished_at", OffsetDateTime::class.java),
            snapshotAt = rs.getObject("snapshot_at", OffsetDateTime::class.java),
            intervalLabel = rs.getString("interval_label"),
            calculatorVersion = rs.getString("calculator_version"),
            status = rs.getString("status"),
            rowCount = if (rs.wasNull()) null else rowCount,
            errorMessage = rs.getString("error_message")
        )
    }

    fun startRun(intervalLabel: String, calculatorVersion: String): Long {
        val sql = """
            INSERT INTO calculator_runs (
                started_at, snapshot_at, interval_label, calculator_version, status
            )
            VALUES (?, ?, ?, ?, 'RUNNING')
            RETURNING id
        """.trimIndent()

        val now = OffsetDateTime.now()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setObject(1, now)
                statement.setObject(2, now)
                statement.setString(3, intervalLabel)
                statement.setString(4, calculatorVersion)

                statement.executeQuery().use { result ->
                    check(result.next()) { "Could not create calculator run" }
                    result.getLong("id")
                }
            }
        }
    }

    fun finishRun(runId: Long, status: String, rowCount: Long, errorMessage: String? = null) {
        val sql = """
            UPDATE calculator_runs
            SET finished_at = ?, status = ?, row_count = ?, error_message = ?
            WHERE id = ?
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { statement ->
                statement.setObject(1, OffsetDateTime.now())
                statement.setString(2, status)
                statement.setLong(3, rowCount)

                if (errorMessage == null) {
                    statement.setNull(4, Types.VARCHAR)
                } else {
                    statement.setString(4, errorMessage)
                }

                statement.setLong(5, runId)
                statement.executeUpdate()
            }
        }
    }
}