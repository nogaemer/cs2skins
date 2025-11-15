package database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:mysql://localhost:3306/skins_schema?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
            driverClassName = "com.mysql.cj.jdbc.Driver"
            username = "root"
            password = "(y6x2N;Z@1H="

            // Connection pool settings
            maximumPoolSize = 10
            minimumIdle = 5
            idleTimeout = 300000 // 5 minutes
            connectionTimeout = 30000 // 30 seconds
            maxLifetime = 1800000 // 30 minutes

            // MySQL optimizations
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
            addDataSourceProperty("useLocalSessionState", "true")
            addDataSourceProperty("rewriteBatchedStatements", "true")
            addDataSourceProperty("cacheResultSetMetadata", "true")
            addDataSourceProperty("cacheServerConfiguration", "true")
            addDataSourceProperty("elideSetAutoCommits", "true")
            addDataSourceProperty("maintainTimeStats", "false")

            addDataSourceProperty("logger", "com.mysql.cj.log.NullLogger")
            addDataSourceProperty("useUsageAdvisor", "false")
            addDataSourceProperty("profileSQL", "false")
        }

        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.create(
                Collections,
                Weapons,
                Rarities,
                WearConditions,
                Skins,
                SkinPrices
            )
        }
    }
}
