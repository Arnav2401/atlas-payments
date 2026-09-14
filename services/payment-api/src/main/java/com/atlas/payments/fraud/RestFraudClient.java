package com.atlas.payments.fraud;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class RestFraudClient implements FraudClient {
    private static final Logger log = LoggerFactory.getLogger(RestFraudClient.class);

    private final RestClient restClient;

    RestFraudClient(RestClient fraudServiceRestClient) {
        this.restClient = fraudServiceRestClient;
    }

    @Override
    @CircuitBreaker(name = "fraud-service", fallbackMethod = "fallback")
    public FraudAssessment assess(FraudAssessmentRequest request) {
        ScoreApiDto.Response response = restClient.post()
                .uri("/score")
                .body(ScoreApiDto.Request.from(request))
                .retrieve()
                .body(ScoreApiDto.Response.class);

        return response.toDomain();
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private FraudAssessment fallback(FraudAssessmentRequest request, Throwable failure) {
        log.warn("fraud service unavailable, using conservative rule fallback for endToEndId={}: {}",
                request.endToEndId(), failure.toString());
        return ConservativeRuleFallback.assess(request);
    }
}
