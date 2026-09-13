package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R07_CHARGE_BEARER_SUPPORTED — chargeBearer must be one of the supported set.
 *
 * <p>The ISO-20022-flavoured values are DEBT, CRED, SHAR and SLEV. Define the enum in this package, not in the DTO — keeping the wire type a String is what lets this rule own the failure instead of Jackson.
 */
public final class ChargeBearerRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R07_CHARGE_BEARER_SUPPORTED;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R07_CHARGE_BEARER_SUPPORTED");
    }
}
