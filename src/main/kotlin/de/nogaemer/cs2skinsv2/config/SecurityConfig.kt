package de.nogaemer.cs2skinsv2.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * TEMPORARY, Phase 0-4 placeholder.
 *
 * Without ANY SecurityConfig bean, spring-boot-starter-security's default auto-configuration
 * locks every endpoint (including /swagger-ui.html) behind form login with a randomly
 * generated password reprinted to the console on every restart -- exactly the "Invalid
 * credentials" wall you just hit.
 *
 * Permits everything for now so Phases 1-4 (Collections/Skins/Trade-ups/Admin controllers)
 * can be built and tested via Swagger without fighting auth on every restart. This gets
 * REPLACED in Phase 5 with the real split: public GET on /api/v1/{collections,skins,tradeups}/**,
 * authenticated() on /api/v1/admin/** -- once AdminController actually exists and there's
 * something concrete worth protecting.
 *
 * Do not consider this a final security posture. It intentionally disables everything.
*/*/*/

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // stateless JSON API, no cookie-based session forms to protect
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }
}
