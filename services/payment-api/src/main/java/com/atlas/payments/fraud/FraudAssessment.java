package com.atlas.payments.fraud;

import java.util.List;

/**
 * The outcome of a fraud check, whichever path produced it.
 *
 * @param source {@link Source#MODEL} from the real fraud service, or
 *        {@link Source#FALLBACK_RULES} when the circuit is open. Surfaced to
 *        the caller rather than hidden, because "was this decision made by the
 *        model or by the fallback" is exactly the fact an ops console needs to
 *        show — a run of FALLBACK_RULES decisions is the signal that the
 *        fraud service is down, distinct from a run of low-probability MODEL
 *        decisions.
 */
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

    /**
     * {@code probability} is {@code null}, not 0.0 or 1.0 — a rule's binary
     * decision is not a continuous confidence score, and inventing one to fill
     * the field would misrepresent a threshold check as a model output. See
     * {@link ConservativeRuleFallback} for what the rule actually is.
     */
    public static FraudAssessment fromFallback(boolean flagged) {
        return new FraudAssessment(flagged, null, Source.FALLBACK_RULES, List.of());
    }
}
