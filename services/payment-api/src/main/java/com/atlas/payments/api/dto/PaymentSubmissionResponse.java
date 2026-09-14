package com.atlas.payments.api.dto;

import com.atlas.payments.fraud.FraudAssessment;
import com.atlas.payments.validation.RejectionReason;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentSubmissionResponse(
        String endToEndId,
        Status status,
        String paymentId,
        List<RejectionDetail> rejections,
        FraudAssessmentDetail fraudAssessment
) {
    public enum Status { ACCEPTED, REJECTED }

    public record RejectionDetail(String code, String field, String message) {}

    public record FraudAssessmentDetail(
            boolean flagged, Double probability, String source, List<FraudAssessment.TopFeature> topFeatures) {
        static FraudAssessmentDetail from(FraudAssessment assessment) {
            return new FraudAssessmentDetail(
                    assessment.flagged(), assessment.probability(),
                    assessment.source().name(), assessment.topFeatures());
        }
    }

    public static PaymentSubmissionResponse accepted(
            String endToEndId, String paymentId, FraudAssessment fraudAssessment) {
        return new PaymentSubmissionResponse(
                endToEndId, Status.ACCEPTED, paymentId, null,
                fraudAssessment == null ? null : FraudAssessmentDetail.from(fraudAssessment));
    }

    public static PaymentSubmissionResponse rejected(String endToEndId, List<RejectionReason> reasons) {
        List<RejectionDetail> details = reasons.stream()
                .map(reason -> new RejectionDetail(
                        reason.ruleId().code(), reason.field(), reason.message()))
                .toList();

        return new PaymentSubmissionResponse(truncate(endToEndId), Status.REJECTED, null, details, null);
    }

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
