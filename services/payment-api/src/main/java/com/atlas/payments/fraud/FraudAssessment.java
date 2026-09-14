package com.atlas.payments.fraud;

import java.util.List;

public record FraudAssessment(
        boolean flagged,
        Double probability,
        Source source,
        List<TopFeature> topFeatures
) {
    public enum Source { MODEL, FALLBACK_RULES }

    public record TopFeature(String feature, Double value, double shapContribution) {}

    public static FraudAssessment fromModel(boolean flagged, double probability, List<TopFeature> topFeatures) {
        return new FraudAssessment(flagged, probability, Source.MODEL, topFeatures);
    }

    public static FraudAssessment fromFallback(boolean flagged) {
        return new FraudAssessment(flagged, null, Source.FALLBACK_RULES, List.of());
    }
}
