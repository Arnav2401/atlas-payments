package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R01_AMOUNT_POSITIVE — the instructed amount must be strictly positive.
 *
 * <p>Decide what a null amount means here versus in R10, so two rules cannot both claim the same failure. Zero is the interesting case: it is not negative, and it is not a payment.
 */
public final class AmountPositiveRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R01_AMOUNT_POSITIVE;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R01_AMOUNT_POSITIVE");
    }
}
