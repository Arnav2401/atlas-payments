package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R09_DEBTOR_COUNTRY — debtor country must be a valid ISO 3166-1 alpha-2 code.
 *
 * <p>java.util.Locale#getISOCountries is the JDK's list. Same caveat as R03: it is bundled data, not a live feed. Decide case sensitivity.
 */
public final class DebtorCountryRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R09_DEBTOR_COUNTRY;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R09_DEBTOR_COUNTRY");
    }
}
