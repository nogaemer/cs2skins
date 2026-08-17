package de.nogaemer.cs2skinsv2.config

import java.sql.Connection
import java.sql.DriverManager

class ClickHouseClientFactory(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val username: String,
    private val password: String
) {
    init {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver")
    }

    private val jdbcUrl: String
        get() = "jdbc:clickhouse://$host:$port/$database"

    fun openConnection(): Connection {
        return DriverManager.getConnection(jdbcUrl, username, password)
    }

    fun <T> query(block: (Connection) -> T): T {
        openConnection().use { connection ->
            return block(connection)
        }
    }

    fun testConnection() {
        query { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version()").use { result ->
                    check(result.next()) { "ClickHouse returned no version" }
                    println("ClickHouse connected: ${result.getString(1)}")
                }
            }
        }
    }
}
