package com.atlas.payments.testing;

/**
 * The one JWT signing secret every full-{@code @SpringBootTest} in this
 * module shares, via {@code @TestPropertySource(properties =
 * "atlas.security.jwt-secret=" + TestSecurity.JWT_SECRET)}.
 *
 * <p>{@code src/main/resources/application.yaml} deliberately has NO default
 * for this property — see {@code JwtService}'s javadoc: a security-critical
 * secret should fail the application at startup if unset, not run with a
 * default a public repo already reveals. Tests need a real value to build a
 * {@code JwtDecoder} against; this fixed, committed string is it. It grants
 * no access to anything outside this build — no test environment it signs
 * tokens for is reachable from anywhere else.
 *
 * <p>Not a {@code src/test/resources/application.yaml}: a same-named
 * resource file there does not layer on top of
 * {@code src/main/resources/application.yaml}, it replaces it outright on
 * the classpath — found the hard way, when adding one silently dropped every
 * other property the main config supplies (fraud-service's base URL, among
 * others) and broke tests that had nothing to do with security at all.
 * {@code @TestPropertySource} adds one property without touching the rest.
 */
public final class TestSecurity {

    public static final String JWT_SECRET = "test-only-jwt-signing-secret-shared-across-this-module-32b+";

    private TestSecurity() {
    }
}
