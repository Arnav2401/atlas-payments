package com.atlas.payments.security;

import com.atlas.payments.testing.TestSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The brief's explicit acceptance bar:</b> an analyst token cannot reach a
 * supervisor endpoint. Against a real, running embedded server
 * ({@code WebEnvironment.RANDOM_PORT}) and the real filter chain — not
 * {@code MockMvc} with security stubbed out, which is exactly what every
 * other controller test in this module deliberately does (see
 * {@code PaymentControllerTest}'s javadoc) because THIS is the one test class
 * whose entire job is to prove that boundary for real.
 *
 * <p>No Kafka container here, unlike {@code LedgerIntegrationTest} and
 * {@code OutboxKafkaIntegrationTest} — this class never exercises the outbox
 * or decision-consumer paths, only HTTP and Postgres, and Spring Kafka's
 * consumer/producer clients connect lazily and retry in the background
 * rather than failing application startup when no broker is reachable
 * (confirmed by running this class with no Kafka running at all — it passes,
 * just several seconds slower while the background retries cycle).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "atlas.security.jwt-secret=" + TestSecurity.JWT_SECRET)
@Testcontainers
class PaymentAuthorizationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    private String baseUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private String tokenFor(String username, String password) {
        var response = rest.postForEntity(
                baseUrl("/auth/token"),
                Map.of("username", username, "password", password),
                Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "token issuance must succeed for a valid demo user");
        return (String) response.getBody().get("accessToken");
    }

    private HttpEntity<?> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(headers);
    }

    // ------------------------------------------------------------- /auth/token

    @Test
    void issues_a_token_for_valid_demo_credentials() {
        String token = tokenFor("analyst1", "analyst-demo-password");
        assertTrue(token != null && !token.isBlank());
    }

    @Test
    void rejects_a_wrong_password() {
        var response = rest.postForEntity(
                baseUrl("/auth/token"),
                Map.of("username", "analyst1", "password", "wrong-password"),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void rejects_a_username_that_does_not_exist() {
        var response = rest.postForEntity(
                baseUrl("/auth/token"),
                Map.of("username", "nobody", "password", "irrelevant"),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // --------------------------------------------------------- where validation happens

    @Test
    void no_token_at_all_is_401_before_any_controller_runs() {
        var response = rest.exchange(baseUrl("/payments"), HttpMethod.GET, bearer(null), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    /**
     * A token with an invalid signature - proving validation actually checks
     * the signature, not just that a bearer header is present. Built by
     * taking a real, validly-ISSUED token and flipping a character in its
     * signature segment, which is a stronger proof than a hand-typed garbage
     * string: this is a token that is correct in every way EXCEPT the one
     * thing signature verification exists to catch.
     */
    @Test
    void a_tampered_signature_is_rejected() {
        String realToken = tokenFor("analyst1", "analyst-demo-password");
        String[] parts = realToken.split("\\.");
        char[] signature = parts[2].toCharArray();
        signature[0] = signature[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + new String(signature);

        var response = rest.exchange(baseUrl("/payments"), HttpMethod.GET, bearer(tampered), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void a_token_signed_with_a_different_key_is_rejected() {
        // Built with its own JwtService instance, a different secret from the
        // one this application's JwtDecoder is configured with - proves
        // validation is against THIS service's key, not merely "any
        // well-formed JWT".
        JwtService foreignIssuer = new JwtService(
                "a-completely-different-signing-key-nothing-to-do-with-this-app", 15, java.time.Clock.systemUTC());
        String foreignToken = foreignIssuer.issue("analyst1", Role.ANALYST);

        var response = rest.exchange(baseUrl("/payments"), HttpMethod.GET, bearer(foreignToken), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // ------------------------------------------------------------------- RBAC

    @Test
    void an_analyst_can_view_payments() {
        String token = tokenFor("analyst1", "analyst-demo-password");
        var response = rest.exchange(baseUrl("/payments"), HttpMethod.GET, bearer(token), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void a_supervisor_can_view_payments_too() {
        String token = tokenFor("supervisor1", "supervisor-demo-password");
        var response = rest.exchange(baseUrl("/payments"), HttpMethod.GET, bearer(token), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    /** <b>The exact claim the brief names.</b> */
    @Test
    void an_analyst_token_cannot_reach_the_funding_endpoint() {
        String token = tokenFor("analyst1", "analyst-demo-password");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
                Map.of("accountNumber", "RBAC-TEST", "amount", 10.00, "currency", "USD"), authHeaders(token));

        var response = rest.postForEntity(baseUrl("/ops/funding"), request, String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "an analyst must never be able to create money");
    }

    @Test
    void a_supervisor_token_can_reach_the_funding_endpoint() {
        String token = tokenFor("supervisor1", "supervisor-demo-password");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
                Map.of("accountNumber", "RBAC-TEST-2", "amount", 10.00, "currency", "USD"), authHeaders(token));

        var response = rest.postForEntity(baseUrl("/ops/funding"), request, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    /** The same distinction, on the fraud-review workflow this time. */
    @Test
    void an_analyst_token_cannot_clear_or_escalate_a_payment() {
        String analystToken = tokenFor("analyst1", "analyst-demo-password");
        // A payment id that does not exist is fine for THIS assertion - RBAC
        // is enforced by @PreAuthorize before the method body (and its
        // not-found lookup) ever runs, so a 403 here proves the authorization
        // boundary regardless of what a 404 vs 403 would otherwise depend on.
        String nonExistentPaymentId = "00000000-0000-0000-0000-000000000000";

        var clearResponse = rest.postForEntity(
                baseUrl("/payments/" + nonExistentPaymentId + "/clear"),
                new HttpEntity<>(authHeaders(analystToken)), String.class);
        var escalateResponse = rest.postForEntity(
                baseUrl("/payments/" + nonExistentPaymentId + "/escalate"),
                new HttpEntity<>(authHeaders(analystToken)), String.class);

        assertEquals(HttpStatus.FORBIDDEN, clearResponse.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, escalateResponse.getStatusCode());
    }

    @Test
    void an_analyst_can_request_review() {
        String analystToken = tokenFor("analyst1", "analyst-demo-password");
        String nonExistentPaymentId = "00000000-0000-0000-0000-000000000000";

        var response = rest.postForEntity(
                baseUrl("/payments/" + nonExistentPaymentId + "/review"),
                new HttpEntity<>(authHeaders(analystToken)), String.class);

        // Not FORBIDDEN - an analyst is allowed to request review. It is 404
        // because this payment does not exist, which is a different boundary
        // (business logic, not authorization) than the one this test suite
        // is about; see PaymentDecisionControllerTest for that one.
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ------------------------------------------------------------- /rings (M6, optional)

    /**
     * No fraud-service running in this test class at all (unlike
     * {@code FraudClientResilienceTest} / {@code RingsClientTest}, which use
     * WireMock) - so this also proves {@link com.atlas.payments.rings.RingsClient}'s
     * graceful degradation end-to-end, through the real controller, not just
     * unit-tested in isolation. The claim under test here is narrower,
     * though: that both roles can reach /rings at all - RBAC, same as
     * viewing payments, since M6's ring view is read-only ops data, not a
     * decision action.
     */
    @Test
    void an_analyst_can_view_rings() {
        String token = tokenFor("analyst1", "analyst-demo-password");
        var response = rest.exchange(baseUrl("/rings"), HttpMethod.GET, bearer(token), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void a_supervisor_can_view_rings_too() {
        String token = tokenFor("supervisor1", "supervisor-demo-password");
        var response = rest.exchange(baseUrl("/rings"), HttpMethod.GET, bearer(token), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
