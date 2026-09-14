package com.atlas.payments.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

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

    public void recordFraudAssessment(String source, boolean flagged) {
        registry.counter(FRAUD_METRIC, "source", source, "flagged", Boolean.toString(flagged)).increment();
    }
}
