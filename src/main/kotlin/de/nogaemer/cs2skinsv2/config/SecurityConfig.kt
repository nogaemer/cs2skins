package de.nogaemer.cs2skinsv2.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${app.security.admin-username:admin}") private val adminUsername: String,
    @Value("\${app.security.admin-password:admin}") private val adminPassword: String
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/v1/admin/**").authenticated()
                    .requestMatchers("/api/v1/collections/**", "/api/v1/skins/**", "/api/v1/tradeups/**").permitAll()
                    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                    .anyRequest().denyAll()
            }
            .httpBasic { }
            .userDetailsService(userDetailsService())

        return http.build()
    }

    @Bean
    fun userDetailsService(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername(adminUsername)
                .password("{noop}$adminPassword")
                .roles("ADMIN")
                .build()
        )

    @Bean
    fun passwordEncoder(): PasswordEncoder =
        PasswordEncoderFactories.createDelegatingPasswordEncoder()
}