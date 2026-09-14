package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;
import java.util.regex.Pattern;

public final class EndToEndIdRule implements ValidationRule {
    private static final String FIELD = "endToEndId";

    public static final int MAX_LENGTH = 35;

    private static final Pattern PERMITTED = Pattern.compile("^[A-Za-z0-9/\\-?:().,'+ ]+$");

    @Override
    public RuleId id() {
        return RuleId.R04_END_TO_END_ID;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        String id = request.endToEndId();

        if (id == null || id.isBlank()) {
            return Optional.of(new RejectionReason(id(), FIELD, "endToEndId is required"));
        }
        if (id.length() > MAX_LENGTH) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "endToEndId exceeds " + MAX_LENGTH + " characters"));
        }
        if (!PERMITTED.matcher(id).matches()) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "endToEndId contains characters outside the permitted set"));
        }
        return Optional.empty();
    }
}
