package de.nogaemer.cs2skinsv2.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun cs2skinsOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("cs2skins_v2 API")
                .description(
                    "CS2 skin trade-up calculator -- browse collections and skins, " +
                        "query rated trade-up recipes, and look up the best trade-up for " +
                        "any skin pair. Backed by PostgreSQL (catalog/reference data) and " +
                        "ClickHouse (trade-up rating snapshots)."
                )
                .version("v1")
        )
}
