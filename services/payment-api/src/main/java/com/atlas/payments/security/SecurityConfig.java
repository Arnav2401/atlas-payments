package com.atlas.payments.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.List;

/**
 * Stateless JWT bearer authentication, plus role-based authorization wired
 * from each token's {@code role} claim.
 *
 * <p>CSRF is disabled deliberately, not left off by default: CSRF protects
 * session-cookie authentication, where a browser attaches credentials to a
 * request automatically and a malicious site can ride on that. A bearer
 * token is never attached automatically — the caller must read it and set
 * the {@code Authorization} header itself — so the attack CSRF protection
 * exists for does not apply here. Session management is
 * {@code STATELESS} for the same reason: nothing about auth is kept on the
 * server between requests, which is also what makes this API safely
 * horizontally scalable without sticky sessions.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // The ops console (ops-console/) is a browser SPA on its own origin -
    // localhost:5173 from `npm run dev`, localhost:3002 from the
    // docker-compose service - calling this API directly with fetch().
    // Unlike a same-origin request, the browser enforces CORS on the
    // response before any JS here ever sees it, so without this the
    // console's requests fail before the JWT check even runs. A short
    // explicit allowlist, not "*": a wildcard would also have to drop
    // credentialed requests (Authorization headers) to stay spec-legal,
    // which defeats the point.
    // Spring's own conversion service splits a comma-separated property
    // string into a List<String> here - no SpEL needed.
    @Value("${atlas.ops-console.allowed-origins:http://localhost:5173,http://localhost:3002}")
    private List<String> opsConsoleOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/auth/token").permitAll()
                        // Prometheus scraping and container health checks need to
                        // reach these without a token. In a real deployment these
                        // would be network-isolated to the scraper/orchestrator
                        // rather than exposed on the same public listener as the
                        // API — a load-balancer or service-mesh concern this
                        // single-instance build does not have the infrastructure
                        // to demonstrate, stated here rather than left implicit.
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(opsConsoleOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtService jwtService) {
        // The same secret key JwtService signs with - see that class's javadoc
        // for where validation actually happens and what it does and does not
        // protect against.
        return NimbusJwtDecoder.withSecretKey(jwtService.signingKey())
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();
    }

    /**
     * Reads the {@code role} claim this service's own {@link JwtService} puts
     * in every token, rather than the {@code scope}/{@code scp} claim Spring
     * Security's default converter expects — that default is shaped for
     * OAuth2 scopes from a real authorization server, which is not what this
     * token carries. {@code hasRole("SUPERVISOR")} then works because the
     * authority is prefixed {@code ROLE_}, Spring Security's own convention
     * for that helper.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString(JwtService.ROLE_CLAIM);
            if (role == null || role.isBlank()) {
                return List.of();
            }
            Collection<GrantedAuthority> authorities = new java.util.ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            return authorities;
        });
        return converter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
