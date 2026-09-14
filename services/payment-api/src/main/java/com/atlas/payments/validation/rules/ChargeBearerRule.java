package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.domain.PaymentInstruction.ChargeBearer;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ChargeBearerRule implements ValidationRule {
    private static final String FIELD = "chargeBearer";

    private static final Set<String> SUPPORTED = Arrays.stream(ChargeBearer.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public RuleId id() {
        return RuleId.R07_CHARGE_BEARER_SUPPORTED;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        String chargeBearer = request.chargeBearer();

        if (chargeBearer == null || chargeBearer.isBlank()) {
            return Optional.of(new RejectionReason(id(), FIELD, "chargeBearer is required"));
        }
        if (!SUPPORTED.contains(chargeBearer)) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "chargeBearer must be one of DEBT, CRED, SHAR, SLEV"));
        }
        return Optional.empty();
    }
}
