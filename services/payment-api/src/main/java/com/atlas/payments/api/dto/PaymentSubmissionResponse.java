package com.atlas.payments.api.dto;

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
        List<RejectionDetail> rejections
) {

    public enum Status { ACCEPTED, REJECTED }

    /**
     * One failed rule as the caller sees it. {@code code} is the published
     * contract; the internal {@link com.atlas.payments.validation.RuleId} is not
     * exposed, so renaming a rule cannot break a client.
     */
    public record RejectionDetail(String code, String field, String message) {}

    public static PaymentSubmissionResponse accepted(String endToEndId, String paymentId) {
        return new PaymentSubmissionResponse(endToEndId, Status.ACCEPTED, paymentId, null);
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

        return new PaymentSubmissionResponse(truncate(endToEndId), Status.REJECTED, null, details);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
