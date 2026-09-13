package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R03_CURRENCY_SUPPORTED — instructed currency must be a live ISO 4217 code.
 *
 * <p>java.util.Currency#getAvailableCurrencies is the JDK's view of ISO 4217. Worth knowing before you rely on it: it includes historical codes, and it tracks the JDK's bundled data rather than a live feed. If you need a curated allow-list instead, that is a defensible choice — write down which you picked and why.
 */
public final class CurrencySupportedRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R03_CURRENCY_SUPPORTED;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R03_CURRENCY_SUPPORTED");
    }
}
