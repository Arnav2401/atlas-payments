package com.atlas.payments.fraud;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * The fraud service's own wire schema — snake_case, matching
 * services/fraud-service/src/fraud_service/api/schemas.py exactly — kept
 * separate from {@link FraudAssessmentRequest}/{@link FraudAssessment} on
 * purpose: this class is the HTTP contract with an external service, and the
 * two domain types are this API's own vocabulary. Collapsing them into one
 * type would mean a schema change in the Python service's JSON silently
 * becomes a change to this API's internal model.
 *
 * <p>Every field is {@code @JsonProperty}-annotated to its snake_case name
 * rather than configuring the shared {@code ObjectMapper} to snake_case
 * globally — this API's own contract with its own callers is camelCase (see
 * M1's {@code PaymentInstructionRequest}), and a global naming strategy
 * change would silently rewrite that contract too.
 */
final class ScoreApiDto {

    private ScoreApiDto() {
    }

    record Request(
            @JsonProperty("end_to_end_id") String endToEndId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("is_cash_out") boolean isCashOut,
            @JsonProperty("debtor_account") String debtorAccount,
            @JsonProperty("creditor_account") String creditorAccount,
            @JsonProperty("debtor_balance_before") BigDecimal debtorBalanceBefore,
            @JsonProperty("creditor_balance_before") BigDecimal creditorBalanceBefore,
            @JsonProperty("hour_of_day") int hourOfDay
    ) {
        static Request from(FraudAssessmentRequest request) {
            return new Request(
                    request.endToEndId(),
                    request.amount(),
                    request.isCashOut(),
                    request.debtorAccount(),
                    request.creditorAccount(),
                    request.debtorBalanceBefore(),
                    request.creditorBalanceBefore(),
                    request.hourOfDay());
        }
    }

    record Response(
            @JsonProperty("end_to_end_id") String endToEndId,
            @JsonProperty("probability") double probability,
            @JsonProperty("flagged") boolean flagged,
            @JsonProperty("threshold") double threshold,
            @JsonProperty("top_features") List<FeatureContribution> topFeatures,
            @JsonProperty("source") String source
    ) {
        FraudAssessment toDomain() {
            List<FraudAssessment.TopFeature> features = topFeatures.stream()
                    .map(f -> new FraudAssessment.TopFeature(f.feature(), f.value(), f.shapContribution()))
                    .toList();
            return FraudAssessment.fromModel(flagged, probability, features);
        }
    }

    record FeatureContribution(
            @JsonProperty("feature") String feature,
            @JsonProperty("value") Double value,
            @JsonProperty("shap_contribution") double shapContribution
    ) {}
}
