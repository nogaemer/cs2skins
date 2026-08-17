package de.nogaemer.cs2skinsv2.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "clickhouse")
data class ClickHouseProperties(
    var host: String = "localhost",
    var port: Int = 8123,
    var database: String = "tradeups",
    var username: String = "",
    var password: String = ""
)

@Configuration
class ClickHouseConfig {

    @Bean
    fun clickHouseProperties(): ClickHouseProperties = ClickHouseProperties()

    @Bean
    fun clickHouseClientFactory(properties: ClickHouseProperties): ClickHouseClientFactory =
        ClickHouseClientFactory(
            host = properties.host,
            port = properties.port,
            database = properties.database,
            username = properties.username,
            password = properties.password
        )
}
