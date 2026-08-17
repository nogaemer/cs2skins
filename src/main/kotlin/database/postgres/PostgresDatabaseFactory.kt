package database.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import config.AppConfig
import org.jetbrains.exposed.sql.Database
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

        // Safe, no-tradeoff win for the batch-insert paths (seeding, price
        // ingestion, recipe/outcome persistence): the same handful of SQL
        // shapes get executed thousands of times with different bind values
        // and different chunk sizes -- caching parsed/planned statements
        // avoids re-parsing that SQL on every call.
        addDataSourceProperty("cachePrepStmts", "true")
        addDataSourceProperty("prepStmtCacheSize", "250")
        addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    })

    // Exposed is only used incidentally right now -- safe to keep or drop later.
    init {
        Database.connect(hikariDataSource)
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
