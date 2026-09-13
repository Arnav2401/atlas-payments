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
}
