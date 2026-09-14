package com.atlas.payments.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies this service's own tokens. Validation happens once, in
 * SecurityConfig's JwtDecoder, before any controller runs.
 *
 * What stops replay: the expiry, and nothing else. A captured, still-valid
 * token works for whoever holds it - signing proves who issued it, not who is
 * presenting it. Closing that needs TLS plus revocation or mTLS binding, none
 * of which is in scope here, so the TTL bounds the window rather than shutting
 * it.
 */
@Component
public class JwtService {
    static final String ROLE_CLAIM = "role";

    private final SecretKeySpec key;
    private final Duration tokenTtl;
    private final Clock clock;

    public JwtService(
            @Value("${atlas.security.jwt-secret}") String secret,
            @Value("${atlas.security.token-ttl-minutes:15}") long tokenTtlMinutes,
            Clock clock) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "atlas.security.jwt-secret must be at least 32 bytes for HS256; got " + keyBytes.length
                            + ". Generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
        this.clock = clock;
    }

    public String issue(String username, Role role) {
        Instant now = Instant.now(clock);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(username)
                .claim(ROLE_CLAIM, role.name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(tokenTtl)))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(key));
        } catch (JOSEException impossible) {
            throw new IllegalStateException("JWT signing failed unexpectedly", impossible);
        }
        return jwt.serialize();
    }

    public long ttlSeconds() {
        return tokenTtl.toSeconds();
    }

    SecretKeySpec signingKey() {
        return key;
    }
}
