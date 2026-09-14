package com.atlas.payments.rings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * A separate {@link RestClient} bean for {@link RingsClient} rather than
 * reusing {@code fraud.FraudClientConfig}'s {@code fraudServiceRestClient} —
 * found necessary the first time this actually ran against a live graph:
 * that bean's 800ms read timeout is tuned tightly for {@code /score}, a
 * per-payment call on the critical path where a slow fraud service must fail
 * fast. {@code GET /rings} is a heavier one-off analytical query (Cypher
 * aggregating over every account clearing an in-degree threshold) loaded
 * once when an analyst opens the ops console's "Fraud rings" tab, and it
 * missed that 800ms window in practice — surfaced as
 * {@code SocketTimeoutException: Read timed out} in payment-api's own logs,
 * not assumed from reading the two call shapes side by side. Same base URL
 * (same host), longer budget for a genuinely different kind of call.
 */
@Configuration
public class RingsClientConfig {

    @Bean
    public RestClient ringsRestClient(@Value("${atlas.fraud-service.base-url}") String baseUrl) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(300))
                .withReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }
}
