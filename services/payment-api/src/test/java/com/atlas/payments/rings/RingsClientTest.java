package com.atlas.payments.rings;

import com.github.tomakehurst.wiremock.WireMockServer;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M6-optional analogue of {@code FraudClientResilienceTest}, minus the
 * circuit breaker — see {@link RingsClient}'s own javadoc for why one call
 * loading the ops console's "Fraud rings" tab doesn't need one. The claim
 * here is narrower and just as real: fraud-service down (or Neo4j down
 * behind it, which looks identical from here) must degrade GET /rings to
 * {@code enabled=false}, not a 500 that breaks the ops console's whole tab.
 */
@SpringBootTest(
        classes = {RingsClient.class, RingsClientConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class RingsClientTest {

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void fraudServiceUrl(DynamicPropertyRegistry registry) {
        wireMock = new WireMockServer(0);
        wireMock.start();
        registry.add("atlas.fraud-service.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    private RingsClient ringsClient;

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @Test
    void a_healthy_fraud_service_returns_its_ranking_unchanged() {
        wireMock.stubFor(get(urlPathEqualTo("/rings")).willReturn(okJson("""
                {"enabled": true, "candidates": [
                  {"account_id": "RING0-MULE", "community": 5, "in_degree": 8,
                   "temporal_spread_hours": 6, "suspicion_score": 1.14, "planted": true}
                ]}
                """)));

        RingsResponse response = ringsClient.fetch(15);

        assertTrue(response.enabled());
        assertEquals(1, response.candidates().size());
        assertEquals("RING0-MULE", response.candidates().get(0).accountId());
        assertTrue(response.candidates().get(0).planted());
    }

    @Test
    void fraud_service_reporting_graph_features_disabled_passes_through_unchanged() {
        wireMock.stubFor(get(urlPathEqualTo("/rings")).willReturn(okJson("""
                {"enabled": false, "candidates": []}
                """)));

        RingsResponse response = ringsClient.fetch(15);

        assertFalse(response.enabled());
        assertTrue(response.candidates().isEmpty());
    }

    @Test
    void an_unreachable_fraud_service_degrades_to_disabled_rather_than_throwing() {
        wireMock.stubFor(get(urlPathEqualTo("/rings")).willReturn(aResponse().withStatus(500)));

        RingsResponse response = ringsClient.fetch(15);

        assertFalse(response.enabled());
        assertTrue(response.candidates().isEmpty());
    }
}
