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

/**
 * R07 — {@code chargeBearer} must be one of the supported set.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>Reuses {@link ChargeBearer} from the domain rather than declaring a
 * second enum in this package.</b> Two enums with the same four constants
 * inevitably drift, and the validation package already depends on the domain
 * through {@code ValidationOutcome.Accepted}. The rule's job is to prove the
 * String can become that enum; the narrowing itself happens in
 * {@code PaymentInstruction.of}.
 *
 * <p><b>Exact match, no normalisation</b> — consistent with R03. These are
 * ISO-20022-flavoured code values, which are uppercase by definition.
 *
 * <p>The four values: DEBT (debtor pays all charges), CRED (creditor pays),
 * SHAR (shared), SLEV (following the service level agreed for the payment).
 */
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
