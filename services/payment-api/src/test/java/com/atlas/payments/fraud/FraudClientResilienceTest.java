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
        registry.add("resilience4j.circuitbreaker.instances.fraud-service.wait-duration-in-open-state",
                () -> "2s");
    }

    @Autowired
    private FraudClient fraudClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

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

    @Test
    void a_single_failed_call_falls_back_immediately_rather_than_propagating() {
        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(aResponse().withStatus(500)));

        FraudAssessment assessment = fraudClient.assess(sampleRequest());

        assertEquals(FraudAssessment.Source.FALLBACK_RULES, assessment.source());
    }

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

        wireMock.stubFor(post(urlEqualTo("/score")).willReturn(okJson("""
                {"end_to_end_id":"E2E-TEST","probability":0.99,"flagged":true,
                 "threshold":0.16,"top_features":[],"source":"MODEL"}
                """)));

        FraudAssessment whileOpen = fraudClient.assess(sampleRequest());
        assertEquals(FraudAssessment.Source.FALLBACK_RULES, whileOpen.source(),
                "an open circuit must not reach the fraud service even once it is healthy again");
    }

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
