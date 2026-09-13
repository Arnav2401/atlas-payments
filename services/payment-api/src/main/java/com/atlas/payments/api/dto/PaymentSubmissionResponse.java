package com.atlas.payments.api.dto;

import java.util.List;

/**
 * Wire response for {@code POST /payments}.
 *
 * <p>Shaped for the 200-with-status reading of DECISION 1 (see
 * {@link com.atlas.payments.api.PaymentController}): it carries an explicit
 * status discriminator so a rejection can be reported on a successful HTTP
 * exchange. If you settle on 4xx-for-rejection instead, this record loses
 * {@code status} and the rejection path moves into the exception handler.
 * Decide before you write the factories.
 */
public record PaymentSubmissionResponse(
        String endToEndId,
        Status status,
        String paymentId,
        List<RejectionDetail> rejections
) {

    public enum Status { ACCEPTED, REJECTED }

    /**
     * One failed rule, as the caller sees it. {@code code} is the published
     * contract; the internal {@link com.atlas.payments.validation.RuleId} is not
     * exposed, so renaming a rule cannot break a client.
     */
    public record RejectionDetail(String code, String field, String message) {}

    // TODO(M1): static factories - accepted(...) and rejected(...) - once
    // DECISION 1 is settled.
}
