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
 * Issues and holds the signing key for this service's own JWTs.
 *
 * <h2>Where the JWT is validated, and what stops replay</h2>
 *
 * <p>Validated in exactly one place: {@link SecurityConfig}'s {@code
 * JwtDecoder}, wired into Spring Security's OAuth2 resource-server filter,
 * which runs on every request before it reaches a controller. It checks the
 * HMAC-SHA256 signature against this same secret key (symmetric — the same
 * key issues and verifies, since this service is its own identity provider;
 * see the class-level "why self-issued" note below) and the {@code exp}
 * claim. A request with an invalid signature or an expired token never
 * reaches application code at all — it is rejected by the filter chain with
 * a 401 before any controller method runs.
 *
 * <p><b>What stops replay: a short expiry, and nothing else.</b> This is
 * worth being precise about rather than overclaiming. A captured, still-valid
 * token can be replayed by whoever captured it — signature validation proves
 * the token was issued by this service, not that the bearer presenting it now
 * is the same party it was issued to. The real defenses against that
 * (refresh-token rotation, a revocation list, mTLS binding, short-lived
 * tokens over TLS only) are a deliberate scope cut for this module: TLS
 * termination is out of scope at this stage (see the README's M5 section),
 * and without transport security a revocation list is theatre — the token is
 * already exposed in transit. The one mitigation actually in place is the
 * {@code token-ttl-minutes} expiry, which bounds the replay window rather
 * than closing it.
 *
 * <h2>Why this service issues its own tokens rather than validating someone
 * else's</h2>
 *
 * <p>A real deployment would federate to an external identity provider
 * (Okta, Cognito, a corporate IdP) and this service would only ever validate,
 * never issue. Standing up an external IdP for a two-role, single-service
 * demo would be exactly the kind of decoration the brief warns against
 * elsewhere ("if you cannot state what breaks without it, it does not go
 * in") — there is no federation requirement here to justify it. Self-issuing
 * with a symmetric key is the honest, minimal mechanism that still
 * demonstrates the real thing being asked for: where a JWT is validated, and
 * what a role claim inside it actually gates.
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
        // HS256 requires a key at least as long as its output (256 bits =
        // 32 bytes) per RFC 7518 - failing fast here, at startup, beats
        // Nimbus rejecting every single sign attempt later with a much less
        // obvious error, or - worse - some libraries silently accepting a
        // weak key.
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
            // MACSigner only throws for a key that is too short, and the
            // constructor above already guarantees at least 32 bytes.
            throw new IllegalStateException("JWT signing failed unexpectedly", impossible);
        }
        return jwt.serialize();
    }

    public long ttlSeconds() {
        return tokenTtl.toSeconds();
    }

    /** For {@link SecurityConfig}'s decoder — the same key that signs must verify. */
    SecretKeySpec signingKey() {
        return key;
    }
}
