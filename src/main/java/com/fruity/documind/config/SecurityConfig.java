package com.fruity.documind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY Phase-0 stub. {@code spring-boot-starter-security} secures every
 * endpoint by default, which would make the Phase-0 {@code /health} smoke test
 * return 401. This opens {@code /health} only and leaves everything else requiring
 * authentication.
 *
 * <p>This is NOT the real security setup — JWT auth, roles, and the login/register
 * flow arrive in Phase 2 (README §7) and will replace this class wholesale.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Phase 1 has NO authentication yet — permit everything. This also removes any
        // per-path matcher ambiguity. Real JWT/RBAC security replaces this in Phase 2.
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}