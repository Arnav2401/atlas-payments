package com.atlas.payments.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Decision-outcome counters — the metric DECISION 1's javadoc names as not
 * optional. With rejections riding on HTTP 200 (see {@link PaymentController}),
 * generic tooling that counts non-2xx responses is blind to this service's
 * actual failure modes: a k6 run would report 0% errors while rejecting every
 * payment, and a status-code Grafana panel would show a healthy service
 * throughout an outage. These counters are the only place that distinction is
 * visible, and README's Grafana panel and k6's pass/fail thresholds both read
 * from here, not from HTTP status codes.
 *
 * <p>One metric name, {@code outcome} as a label, not four separately-named
 * counters — the idiomatic Micrometer/Prometheus shape, and what lets a single
 * Grafana panel break down {@code sum by (outcome) (rate(...))} instead of
 * needing one query per outcome wired in by hand.
 */
@Component
public class PaymentMetrics {

    private static final String DECISIONS_METRIC = "atlas.payments.decisions";
    private static final String FRAUD_METRIC = "atlas.payments.fraud_assessments";

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordAccepted() {
        registry.counter(DECISIONS_METRIC, "outcome", "ACCEPTED").increment();
    }

    public void recordRejectedByValidation() {
        registry.counter(DECISIONS_METRIC, "outcome", "REJECTED_VALIDATION").increment();
    }

    public void recordRejectedByLedger() {
        registry.counter(DECISIONS_METRIC, "outcome", "REJECTED_LEDGER").increment();
    }

    /**
     * Separate from the accept/reject outcome above on purpose: a payment can
     * be {@code ACCEPTED} and {@code flagged} at the same time (see DECISION 3
     * — a flag does not block acceptance), so folding this into the same
     * metric would force a choice between two things that are not mutually
     * exclusive. {@code source} (MODEL vs FALLBACK_RULES) is a label here for
     * the same reason it is a field on {@code FraudAssessment}: a spike in
     * FALLBACK_RULES decisions is the signal that the fraud service is down,
     * and that needs to be visible in Grafana without grepping logs for it.
     */
    public void recordFraudAssessment(String source, boolean flagged) {
        registry.counter(FRAUD_METRIC, "source", source, "flagged", Boolean.toString(flagged)).increment();
    }
}
