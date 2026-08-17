package database.clickhouse

import config.AppConfig
import java.sql.Connection
import java.sql.DriverManager

class ClickHouseClientFactory {

    init {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver")
    }

    fun openConnection(): Connection {
        return DriverManager.getConnection(
            AppConfig.clickhouseJdbcUrl,
            AppConfig.CLICKHOUSE_USER,
            AppConfig.CLICKHOUSE_PASSWORD
        )
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
                    check(result.next()) {
                        "ClickHouse returned no version"
                    }

                    println("ClickHouse connected: ${result.getString(1)}")
                }
            }
        }
    }
}