package de.nogaemer.cs2skinsv2.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Allows the React dev server(s) to call this API directly during local development.
 * Hardcoded origins for now -- move to an `app.cors.allowed-origins` list in application.yml
 * once a real production frontend origin exists, so it can differ per environment without
 * a recompile.
 */
@Configuration
class CorsConfig {

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = listOf(
                "http://localhost:3000", // Create React App / Next.js default
                "http://localhost:5173"  // Vite default
            )
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
        }

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", configuration)
        return source
    }
}
