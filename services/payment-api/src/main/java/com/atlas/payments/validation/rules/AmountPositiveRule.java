package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.math.BigDecimal;
import java.util.Optional;

public final class AmountPositiveRule implements ValidationRule {
    private static final String FIELD = "instructedAmount";

    @Override
    public RuleId id() {
        return RuleId.R01_AMOUNT_POSITIVE;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        BigDecimal amount = request.instructedAmount();

        if (amount == null) {
            return Optional.of(new RejectionReason(id(), FIELD, "instructedAmount is required"));
        }
        if (amount.signum() <= 0) {
            return Optional.of(new RejectionReason(
                    id(), FIELD, "instructedAmount must be strictly greater than zero"));
        }
        return Optional.empty();
    }
}
