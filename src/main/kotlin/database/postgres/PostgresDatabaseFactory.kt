package database.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import config.AppConfig
import javax.sql.DataSource

class PostgresDatabaseFactory {
    private val hikariDataSource: HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = AppConfig.postgresJdbcUrl
        driverClassName = "org.postgresql.Driver"
        username = AppConfig.POSTGRES_USER
        password = AppConfig.POSTGRES_PASSWORD
        maximumPoolSize = 16
        minimumIdle = 2
        connectionTimeout = 30000
        idleTimeout = 300000
        maxLifetime = 1800000

        addDataSourceProperty("cachePrepStmts", "true")
        addDataSourceProperty("prepStmtCacheSize", "250")
        addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    })

    init {
        testConnection()
    }

    fun dataSource(): DataSource = hikariDataSource

    fun testConnection() {
        hikariDataSource.connection.use { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT version()").use { result ->
                    check(result.next()) { "PostgreSQL returned no version" }
                    println("PostgreSQL connected: ${result.getString(1)}")
                }
            }
        }
    }

    fun close() = hikariDataSource.close()
}