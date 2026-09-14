package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;

public final class AmountScaleRule implements ValidationRule {
    private static final String FIELD = "instructedAmount";

    @Override
    public RuleId id() {
        return RuleId.R02_AMOUNT_SCALE_MATCHES_CURRENCY;
    }

    @Override
    public Phase phase() {
        return Phase.SEMANTIC;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        BigDecimal amount = request.instructedAmount();
        Currency currency = currencyOrNull(request.instructedCurrency());

        if (amount == null || currency == null) {
            return Optional.empty();
        }

        int minorUnits = currency.getDefaultFractionDigits();
        if (minorUnits < 0) {
            return Optional.of(new RejectionReason(id(), "instructedCurrency",
                    currency.getCurrencyCode() + " has no minor unit and cannot carry an amount"));
        }

        if (amount.scale() > minorUnits) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "instructedAmount has " + amount.scale() + " decimal places; "
                            + currency.getCurrencyCode() + " permits at most " + minorUnits));
        }
        return Optional.empty();
    }

    private static Currency currencyOrNull(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException unknownCode) {
            return null;
        }
    }
}
