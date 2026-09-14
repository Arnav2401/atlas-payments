package com.atlas.payments.fraud;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class FraudClientConfig {

    /**
     * Bounds a single call, independent of the circuit breaker — see
     * {@link RestFraudClient}'s javadoc for why both layers exist. A short
     * connect timeout and a slightly longer read timeout: a fraud service
     * that never accepts the connection should fail fast, and inference
     * itself has real work to do (feature reads plus SHAP), so it gets more
     * room before being called "slow" — matched to the circuit breaker's own
     * {@code slowCallDurationThreshold} in application.yaml, not chosen
     * independently of it.
     *
     * <p>{@code .simple()} rather than {@code .detect()} — deliberately, not
     * merely because it was left at the default. {@code detect()} picks the
     * JDK's own {@code java.net.http.HttpClient} here (nothing else is on the
     * classpath), which attempts an HTTP/2 upgrade by default; against
     * WireMock's Jetty-backed test server that negotiation failed outright
     * ({@code RST_STREAM: Stream cancelled}) before a single test could pass.
     * The fraud service is uvicorn/FastAPI, plain HTTP/1.1, and this is a
     * single synchronous request per payment, not a high-throughput streaming
     * client — there is no HTTP/2 benefit being given up, and {@code simple()}
     * (blocking {@code HttpURLConnection}, HTTP/1.1 only) removes a whole class
     * of negotiation failure for a call this small.
     */
    @Bean
    public RestClient fraudServiceRestClient(
            @Value("${atlas.fraud-service.base-url}") String baseUrl) {

        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(300))
                .withReadTimeout(Duration.ofMillis(800));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.simple().build(settings))
                .build();
    }
}
