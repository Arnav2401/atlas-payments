package com.atlas.payments.api.dto;

import com.atlas.payments.fraud.FraudAssessment;
import com.atlas.payments.validation.RejectionReason;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wire response for {@code POST /payments}.
 *
 * <p>Carries an explicit status discriminator because both outcomes are returned
 * on 200 — see DECISION 1 in {@link com.atlas.payments.api.PaymentController}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentSubmissionResponse(
        String endToEndId,
        Status status,
        String paymentId,
        List<RejectionDetail> rejections,
        FraudAssessmentDetail fraudAssessment
) {

    public enum Status { ACCEPTED, REJECTED }

    /**
     * One failed rule as the caller sees it. {@code code} is the published
     * contract; the internal {@link com.atlas.payments.validation.RuleId} is not
     * exposed, so renaming a rule cannot break a client.
     */
    public record RejectionDetail(String code, String field, String message) {}

    /**
     * The wire shape of a {@link FraudAssessment}. A separate type, not the
     * domain record reused directly, for the same reason {@code RejectionDetail}
     * exists: the wire contract and the internal model are allowed to diverge
     * without one edit forcing the other, and today they already do — this type
     * has no {@code topFeatures} entry duplicated per field, it carries the list
     * as-is.
     */
    public record FraudAssessmentDetail(
            boolean flagged, Double probability, String source, List<FraudAssessment.TopFeature> topFeatures) {

        static FraudAssessmentDetail from(FraudAssessment assessment) {
            return new FraudAssessmentDetail(
                    assessment.flagged(), assessment.probability(),
                    assessment.source().name(), assessment.topFeatures());
        }
    }

    /**
     * @param fraudAssessment {@code null} when the payment was a replay (see
     *        {@code PaymentController#assessFraud}) — a replay is not scored a
     *        second time, so there is no assessment to report, not an empty one.
     */
    public static PaymentSubmissionResponse accepted(
            String endToEndId, String paymentId, FraudAssessment fraudAssessment) {
        return new PaymentSubmissionResponse(
                endToEndId, Status.ACCEPTED, paymentId, null,
                fraudAssessment == null ? null : FraudAssessmentDetail.from(fraudAssessment));
    }

    /**
     * @param endToEndId echoed so the caller can correlate. It is echoed
     *        <em>truncated</em>, because on the rejection path it is by
     *        definition unvalidated — R04 may be the very rule that failed — and
     *        a response must not be an amplifier for whatever the caller sent.
     *        Jackson escapes control characters, so the remaining risk is size,
     *        and this bounds it.
     */
    public static PaymentSubmissionResponse rejected(String endToEndId, List<RejectionReason> reasons) {
        List<RejectionDetail> details = reasons.stream()
                .map(reason -> new RejectionDetail(
                        reason.ruleId().code(), reason.field(), reason.message()))
                .toList();

        return new PaymentSubmissionResponse(truncate(endToEndId), Status.REJECTED, null, details, null);
    }

    /**
     * A rejection decided by the ledger rather than by the rule set — the rules
     * cannot see account state, so this arrives after validation passed.
     */
    public static PaymentSubmissionResponse rejectedByLedger(
            String endToEndId, String code, String field, String message) {
        return new PaymentSubmissionResponse(
                truncate(endToEndId), Status.REJECTED, null,
                List.of(new RejectionDetail(code, field, message)), null);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
