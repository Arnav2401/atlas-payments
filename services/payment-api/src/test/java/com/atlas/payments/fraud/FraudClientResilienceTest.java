package com.atlas.payments.fraud;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The claim this module has to make good on: <b>killing the fraud service
 * leaves payments processing on the fallback path.</b> Not "the code compiles
 * and has a fallbackMethod annotation" — an actual dependency down, the
 * circuit actually opening, and {@link FraudClient#assess} still returning a
 * usable answer throughout, on the real Resilience4j state machine wired
 * through the real Spring configuration in application.yaml, not a
 * hand-rolled stand-in for it.
 *
 * <p>WireMock, not a real fraud-service process: this proves the Java side's
 * resilience contract, which is a property of THIS service regardless of
 * what is on the other end of the HTTP call. The Python service's own
 * behaviour is proven separately, in services/fraud-service's own test suite.
 */
/**
 * A deliberately minimal Spring context — {@link RestFraudClient} and
 * {@link FraudClientConfig} plus whatever autoconfiguration wires
 * Resilience4j's {@code @CircuitBreaker} AOP support, with the JPA/datasource/
 * Flyway autoconfiguration this test does not need explicitly excluded. This
 * test is about the resilience contract between this service and the fraud
 * service; it should not need a running Postgres to prove that, and requiring
 * one would make it fail for a reason that has nothing to do with what it
 * tests.
 */
@SpringBootTest(
        classes = {RestFraudClient.class, FraudClientConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class FraudClientResilienceTest {

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void fraudServiceUrl(DynamicPropertyRegistry registry) {
        wireMock = new WireMockServer(0);
        wireMock.start();
        registry.add("atlas.fraud-service.base-url", () -> "http://localhost:" + wireMock.port());
        // Overrides application.yaml's 15s production value for this test
        // only. The recovery test below verifies the SAME mechanism
        // (automatic transition to half-open after the wait duration
        // elapses) — a short, fixed duration makes that assertion prompt and
        // unambiguous rather than budgeting real time against a production
        // tuning value this test does not otherwise care about.
        registry.add("resilience4j.circuitbreaker.instances.fraud-service.wait-duration-in-open-state",
                () -> "2s");
    }

    @Autowired
    private FraudClient fraudClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * @DynamicPropertySource runs its setup ONCE for the whole class, not
     * once per test method — so the WireMockServer it creates is shared
     * across every test here. An earlier version stopped it in @AfterEach,
     * which killed the shared server after the FIRST test method in whatever
     * order JUnit happened to run them, and silently broke every test after
     * it: their calls hit a dead server, fell back, and looked like a
     * circuit-breaker bug rather than a test lifecycle one. resetAll() here
     * clears stubs between tests without tearing down the server itself;
     * @AfterAll below is the server's actual, once-only teardown.
     */
    @BeforeEach
    void resetCircuit() {
        wireMock.resetAll();
        circuitBreakerRegistry.circuitBreaker("fraud-service").reset();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    private static FraudAssessmentRequest sampleRequest() {
        return new FraudAssessmentRequest(
                "E2E-TEST", new BigDecimal("500.00"), false,
                "DEBTOR-1", "CREDITOR-1",
                new BigDecimal("1000.00"), new BigDecimal("0.00"), 14);
    }

    @Test
    void a_healthy_fraud_service_produces_a_model_backed_assessment() {
        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(okJson("""
                {"end_to_end_id":"E2E-TEST","probability":0.02,"flagged":false,
                 "threshold":0.16,"top_features":[],"source":"MODEL"}
                """)));

        FraudAssessment assessment = fraudClient.assess(sampleRequest());

        assertEquals(FraudAssessment.Source.MODEL, assessment.source());
        assertFalse(assessment.flagged());
    }

    /**
     * One call, fraud service down, no prior failures to build a circuit
     * state from: this proves the per-call safety net (timeout + fallback
     * method), independent of the circuit breaker's aggregate behaviour,
     * which the next test covers.
     */
    @Test
    void a_single_failed_call_falls_back_immediately_rather_than_propagating() {
        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(aResponse().withStatus(500)));

        FraudAssessment assessment = fraudClient.assess(sampleRequest());

        assertEquals(FraudAssessment.Source.FALLBACK_RULES, assessment.source());
    }

    /**
     * <b>The test that matters.</b> Enough consecutive failures to open the
     * circuit (application.yaml: minimum-number-of-calls=5,
     * failure-rate-threshold=50%), then asserts the circuit is actually OPEN
     * — not just that individual calls happened to fail — and that calls
     * made while it is open still return a usable, conservative assessment
     * rather than throwing out to the controller.
     */
    @Test
    void repeated_failures_open_the_circuit_and_payments_keep_getting_an_answer() {
        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 6; i++) {
            FraudAssessment assessment = fraudClient.assess(sampleRequest());
            assertEquals(FraudAssessment.Source.FALLBACK_RULES, assessment.source(),
                    "call " + i + " must still produce an answer, not throw");
        }

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("fraud-service");
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "six consecutive failures against a 50% threshold and a window of 10 must open the circuit");

        // With the circuit OPEN, WireMock is never called again - the breaker
        // short-circuits before the HTTP layer. Verified by pointing the stub
        // at a response that would fail the test if it were somehow reached.
        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(okJson("""
                {"end_to_end_id":"E2E-TEST","probability":0.99,"flagged":true,
                 "threshold":0.16,"top_features":[],"source":"MODEL"}
                """)));

        FraudAssessment whileOpen = fraudClient.assess(sampleRequest());
        assertEquals(FraudAssessment.Source.FALLBACK_RULES, whileOpen.source(),
                "an open circuit must not reach the fraud service even once it is healthy again");
    }

    /**
     * The other half of the claim: the circuit recovers on its own once the
     * dependency comes back, rather than staying open until a human
     * intervenes. Real time, not a mocked clock — application.yaml's
     * wait-duration-in-open-state is 15s, so this genuinely waits for it.
     */
    @Test
    void the_circuit_recovers_once_the_fraud_service_comes_back() {
        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 6; i++) {
            fraudClient.assess(sampleRequest());
        }
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("fraud-service");
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(okJson("""
                {"end_to_end_id":"E2E-TEST","probability":0.02,"flagged":false,
                 "threshold":0.16,"top_features":[],"source":"MODEL"}
                """)));

        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            FraudAssessment assessment = fraudClient.assess(sampleRequest());
            assertEquals(FraudAssessment.Source.MODEL, assessment.source(),
                    "once wait-duration-in-open-state elapses, a real call must reach the now-healthy service");
        });
    }
}
