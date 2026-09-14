package com.atlas.payments.api.dto;

public record ApiError(String code, String message) {
    public static final String MALFORMED_BODY = "ATLAS-E001";

    public static final String MISSING_HEADER = "ATLAS-E002";

    public static final String IDEMPOTENCY_CONFLICT = "ATLAS-E003";

    public static final String LEDGER_CONFLICT = "ATLAS-E004";

    public static final String CONCURRENT_MODIFICATION = "ATLAS-E005";

    public static final String INVALID_CREDENTIALS = "ATLAS-E006";

    public static final String REVIEW_CONFLICT = "ATLAS-E007";

    public static final String PAYMENT_NOT_FOUND = "ATLAS-E008";
}
