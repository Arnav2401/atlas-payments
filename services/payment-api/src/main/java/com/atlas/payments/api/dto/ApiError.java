package com.atlas.payments.api.dto;

/**
 * A failure that occurred before any validation rule ran.
 *
 * <p>Deliberately a different shape from {@link PaymentSubmissionResponse}: a
 * client that cannot distinguish "your JSON is broken" from "your currency is
 * not supported" cannot act on either. The first is a bug in the caller's code,
 * the second is a bug in the caller's data.
 *
 * <p>Codes are in the reserved {@code ATLAS-E***} range, never {@code ATLAS-V***}.
 */
public record ApiError(String code, String message) {

    /** The request body could not be read as a payment instruction. */
    public static final String MALFORMED_BODY = "ATLAS-E001";

    /** A required header was absent. */
    public static final String MISSING_HEADER = "ATLAS-E002";

    /** The idempotency key has already been used for a different payment. */
    public static final String IDEMPOTENCY_CONFLICT = "ATLAS-E003";

    /** The payment cannot be posted as instructed. */
    public static final String LEDGER_CONFLICT = "ATLAS-E004";

    /** A concurrent payment on the same account won the version check. Retryable. */
    public static final String CONCURRENT_MODIFICATION = "ATLAS-E005";

    /** POST /auth/token with a wrong username or password. */
    public static final String INVALID_CREDENTIALS = "ATLAS-E006";

    /** A review-workflow transition on an already-resolved payment. */
    public static final String REVIEW_CONFLICT = "ATLAS-E007";

    /** No payment exists with the given id. */
    public static final String PAYMENT_NOT_FOUND = "ATLAS-E008";
}
