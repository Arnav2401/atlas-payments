package com.atlas.payments.rings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the fraud service's {@code GET /rings} through its own
 * {@code ringsRestClient} bean ({@link RingsClientConfig}) — NOT the
 * {@code fraudServiceRestClient} bean {@code RestFraudClient} calls
 * {@code /score} through, despite both hitting the same host. See
 * {@link RingsClientConfig}'s javadoc for why sharing that bean was tried
 * first and produced a real, observed timeout.
 *
 * <h2>Why no circuit breaker here, unlike {@code RestFraudClient}</h2>
 *
 * <p>{@code /score} is on every payment's critical path — a hung fraud
 * service call there would eventually starve every request thread, which is
 * exactly what {@code RestFraudClient}'s circuit breaker exists to prevent.
 * {@code /rings} is loaded once when an analyst opens the ops console's
 * "Fraud rings" tab — a plain try/catch that degrades to
 * {@code enabled=false} on any failure is proportionate to that; a circuit
 * breaker here would be defending a request volume this endpoint will never
 * see.
 */
@Component
public class RingsClient {

    private static final Logger log = LoggerFactory.getLogger(RingsClient.class);

    private final RestClient restClient;

    public RingsClient(@Qualifier("ringsRestClient") RestClient ringsRestClient) {
        this.restClient = ringsRestClient;
    }

    public RingsResponse fetch(int topK) {
        try {
            RingsApiDto.Response response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/rings").queryParam("top_k", topK).build())
                    .retrieve()
                    .body(RingsApiDto.Response.class);

            return new RingsResponse(
                    response.enabled(),
                    response.candidates().stream().map(RingCandidateResponse::from).toList());
        } catch (RestClientException unreachable) {
            log.warn("fraud-service GET /rings unreachable — reporting disabled rather than failing the ops console", unreachable);
            return new RingsResponse(false, java.util.List.of());
        }
    }
}
