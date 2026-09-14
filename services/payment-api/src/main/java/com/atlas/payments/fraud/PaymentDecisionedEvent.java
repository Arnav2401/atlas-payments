package com.atlas.payments.fraud;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The {@code payments.decisioned} event body, published by the fraud service
 * after it consumes a {@link com.atlas.payments.outbox.PaymentSubmittedEvent}.
 * snake_case, matching {@link ScoreApiDto} — same publisher, same convention.
 *
 * <p><b>{@code threshold} was missing from this record for a while, and it is
 * worth saying so.</b> The Python producer
 * (services/fraud-service/src/fraud_service/kafka/events.py) always included
 * it; this record did not declare it, and with
 * {@code spring.jackson.deserialization.fail-on-unknown-properties: true} —
 * set deliberately back in M1, for exactly this class of mistake — every
 * single real decision message failed to deserialise and was retried, then
 * routed to {@code payments.dlq}. Every unit and integration test in this
 * codebase passed the whole time, because they all built
 * {@code PaymentDecisionedEvent} objects in Java and serialised THOSE, which
 * can never disagree with the record's own fields. Only running the real
 * Python service end to end and checking Postgres for the actual row — not
 * trusting that green tests meant the pipeline worked — caught it.
 */
public record PaymentDecisionedEvent(
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("probability") Double probability,
        @JsonProperty("flagged") boolean flagged,
        @JsonProperty("threshold") double threshold,
        @JsonProperty("source") String source,
        @JsonProperty("top_features") List<ScoreApiDto.FeatureContribution> topFeatures
) {}
