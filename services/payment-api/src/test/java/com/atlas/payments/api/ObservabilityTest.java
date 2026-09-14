package com.atlas.payments.api;

import com.atlas.payments.testing.TestSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the metric actually appears at the actual scrape endpoint after a
 * real request through the real filter chain — not that a counter object was
 * incremented in isolation, which would not catch a wiring mistake (a wrong
 * metric name, a controller that never got the {@link PaymentMetrics} bean
 * injected, {@code /actuator/prometheus} accidentally requiring auth) that a
 * unit test of {@code PaymentMetrics} alone could not see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "atlas.security.jwt-secret=" + TestSecurity.JWT_SECRET)
@Testcontainers
class ObservabilityTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void the_prometheus_endpoint_is_reachable_without_a_token() {
        var response = rest.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful());
    }

    @Test
    void a_rejected_payment_increments_the_decision_outcome_counter() {
        // A payment guaranteed to fail R01 (amount must be positive) — the
        // simplest way to produce a REJECTED_VALIDATION outcome without
        // needing a funded account or a valid bearer token for /payments
        // itself, which is a separate concern from this test.
        String body = """
                {"endToEndId":"OBS-TEST","instructedAmount":-1,"instructedCurrency":"USD",
                 "debtorAgent":"DEUTDEFF","creditorAgent":"CHASUS33XXX",
                 "debtorAccount":"OBS-DEBTOR","creditorAccount":"OBS-CREDITOR",
                 "debtorCountry":"DE","chargeBearer":"SHAR","settlementDate":"%s"}
                """.formatted(LocalDate.now().plusDays(7));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "obs-test-" + System.nanoTime());
        // /payments requires authentication (anyRequest().authenticated() in
        // SecurityConfig) - any valid token works here, since submission
        // itself is not role-gated (see PaymentController's DECISION 2 area
        // and SecurityConfig's javadoc for what IS role-gated and why).
        var tokenResponse = rest.postForEntity(
                "http://localhost:" + port + "/auth/token",
                Map.of("username", "analyst1", "password", "analyst-demo-password"),
                Map.class);
        headers.setBearerAuth((String) tokenResponse.getBody().get("accessToken"));

        rest.exchange("http://localhost:" + port + "/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        var metrics = rest.getForEntity("http://localhost:" + port + "/actuator/prometheus", String.class);

        assertTrue(metrics.getStatusCode() == HttpStatus.OK);
        assertTrue(metrics.getBody().contains("atlas_payments_decisions_total")
                        && metrics.getBody().contains("outcome=\"REJECTED_VALIDATION\""),
                "the decision-outcome counter must be visible at the real scrape endpoint");
    }
}
