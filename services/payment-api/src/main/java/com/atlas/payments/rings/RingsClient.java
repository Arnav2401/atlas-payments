package com.atlas.payments.rings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
public class RingsClient {
    private static final Logger log = LoggerFactory.getLogger(RingsClient.class);
    private static final RingsResponse DISABLED = new RingsResponse(false, List.of());

    private final RestClient restClient;

    public RingsClient(@Qualifier("ringsRestClient") RestClient ringsRestClient) {
        this.restClient = ringsRestClient;
    }

    // Any failure degrades to "disabled" rather than propagating: this backs an
    // ops-console tab, not a payment, so a dead graph should grey the tab out.
    public RingsResponse fetch(int topK) {
        try {
            RingsApiDto.Response response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/rings").queryParam("top_k", topK).build())
                    .retrieve()
                    .body(RingsApiDto.Response.class);

            if (response == null || response.candidates() == null) {
                return DISABLED;
            }
            return new RingsResponse(
                    response.enabled(),
                    response.candidates().stream().map(RingCandidateResponse::from).toList());
        } catch (RestClientException unreachable) {
            log.warn("fraud-service GET /rings unreachable, reporting graph features as disabled", unreachable);
            return DISABLED;
        }
    }
}
