package com.atlas.payments.fraud;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PaymentDecisionedEvent(
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("probability") Double probability,
        @JsonProperty("flagged") boolean flagged,
        @JsonProperty("threshold") double threshold,
        @JsonProperty("source") String source,
        @JsonProperty("top_features") List<ScoreApiDto.FeatureContribution> topFeatures
) {}
