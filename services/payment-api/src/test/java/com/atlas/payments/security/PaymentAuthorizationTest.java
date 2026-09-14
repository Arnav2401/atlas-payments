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

    @Test
    void no_token_at_all_is_401_before_any_controller_runs() {
        var response = rest.exchange(baseUrl("/payments"), HttpMethod.GET, bearer(null), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

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
        JwtService foreignIssuer = new JwtService(
                "a-completely-different-signing-key-nothing-to-do-with-this-app", 15, java.time.Clock.systemUTC());
        String foreignToken = foreignIssuer.issue("analyst1", Role.ANALYST);

        var response = rest.exchange(baseUrl("/payments"), HttpMethod.GET, bearer(foreignToken), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

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

    @Test
    void an_analyst_token_cannot_clear_or_escalate_a_payment() {
        String analystToken = tokenFor("analyst1", "analyst-demo-password");
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

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

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
