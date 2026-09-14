package com.atlas.payments.fraud;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the real fraud service over HTTP, behind a circuit breaker, with
 * {@link ConservativeRuleFallback} as the fallback method.
 *
 * <h2>Why a circuit breaker and not just a timeout</h2>
 *
 * <p>A timeout alone (configured on the {@link RestClient} itself — see
 * {@code FraudClientConfig}) bounds how long ONE call can hang. It does
 * nothing about the case that actually matters operationally: the fraud
 * service is down, and every payment for the next several minutes is about to
 * pay that same timeout cost one at a time, serially, while still ultimately
 * falling back. The circuit breaker is what turns "wait 2 seconds, then fall
 * back" into "the ninth failure in the window opens the circuit, and the next
 * hundred payments fall back in microseconds" — the timeout bounds a single
 * request's cost; the breaker bounds the cost of a sustained outage.
 *
 * <h2>Why {@code @CircuitBreaker} with a Spring AOP proxy, not a manual try/catch</h2>
 *
 * <p>The alternative is a hand-written try/catch around the HTTP call with a
 * manual failure counter. That reimplements — worse, and untested against the
 * standard cases — what Resilience4j already does: a sliding window of recent
 * outcomes, a half-open probe state to recover automatically once the fraud
 * service comes back, and separate handling for slow calls versus failed
 * ones. See {@code application.yaml} for the actual thresholds.
 *
 * <p>Package-private, deliberately: {@link PaymentController} depends on
 * {@link FraudClient}, never on this class, so nothing outside this package
 * can bypass the circuit breaker by calling the HTTP path directly.
 */
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

    /**
     * Resilience4j's contract: same parameter list as the guarded method plus
     * a trailing {@link Throwable}, invoked on any failure OR any call the
     * breaker's {@code slowCallDurationThreshold} judges too slow — not only
     * on the circuit being fully open. A single slow call under a closed
     * circuit lands here too, which is correct: a payment should not wait out
     * a hung fraud service just because the ninth failure hasn't happened yet.
     *
     * <p>The throwable is logged, not inspected. Every failure mode — timeout,
     * connection refused, a 500 from the fraud service, the circuit already
     * open — gets the identical conservative response. A payments system
     * should not make its safety net's behaviour depend on which specific way
     * the safety net's dependency happened to fail.
     */
    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    private FraudAssessment fallback(FraudAssessmentRequest request, Throwable failure) {
        log.warn("fraud service unavailable, using conservative rule fallback for endToEndId={}: {}",
                request.endToEndId(), failure.toString());
        return ConservativeRuleFallback.assess(request);
    }
}
