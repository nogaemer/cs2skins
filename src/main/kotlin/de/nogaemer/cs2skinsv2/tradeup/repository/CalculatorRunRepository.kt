package de.nogaemer.cs2skinsv2.tradeup.repository

import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.OffsetDateTime
import javax.sql.DataSource

@Repository
class CalculatorRunRepository(
    private val dataSource: DataSource
) {

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