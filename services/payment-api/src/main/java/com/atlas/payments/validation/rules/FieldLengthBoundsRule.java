package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class FieldLengthBoundsRule implements ValidationRule {
    public static final int MAX_ACCOUNT_LENGTH = 34;

    public static final int MAX_TOTAL_CHARACTERS = 512;

    @Override
    public RuleId id() {
        return RuleId.R10_FIELD_LENGTH_BOUNDS;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        Map<String, String> bounded = new LinkedHashMap<>();
        bounded.put("debtorAccount", request.debtorAccount());
        bounded.put("creditorAccount", request.creditorAccount());

        for (Map.Entry<String, String> field : bounded.entrySet()) {
            String value = field.getValue();
            if (value != null && value.length() > MAX_ACCOUNT_LENGTH) {
                return Optional.of(new RejectionReason(id(), field.getKey(),
                        field.getKey() + " exceeds " + MAX_ACCOUNT_LENGTH + " characters"));
            }
        }

        if (totalCharacters(request) > MAX_TOTAL_CHARACTERS) {
            return Optional.of(new RejectionReason(id(), "*",
                    "payload exceeds " + MAX_TOTAL_CHARACTERS + " characters across all fields"));
        }
        return Optional.empty();
    }

    private static int totalCharacters(PaymentInstructionRequest request) {
        return length(request.endToEndId())
                + length(request.instructedCurrency())
                + length(request.debtorAgent())
                + length(request.creditorAgent())
                + length(request.debtorAccount())
                + length(request.creditorAccount())
                + length(request.debtorCountry())
                + length(request.chargeBearer());
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
