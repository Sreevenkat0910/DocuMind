package com.fruity.documind.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Phase 2 auth beans: password hashing plus HS256 JWT encode/decode over a shared symmetric
 * secret ({@code documind.jwt.secret}, from the {@code JWT_SECRET} env var). We use Spring's
 * native Nimbus JWT support (already on the classpath via the oauth2-resource-server starter)
 * rather than adding a third-party JWT library.
 */
@Configuration
public class SecurityBeans {

    private final SecretKeySpec key;

    public SecurityBeans(@Value("${documind.jwt.secret:}") String secret) {
        // Fail loud at startup rather than silently signing with a weak/empty key.
        // HS256 requires a key of at least 256 bits (32 bytes).
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "documind.jwt.secret must be set and at least 32 bytes long (set JWT_SECRET in .env)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
