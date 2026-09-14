package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Currency;
import java.util.Optional;
import java.util.Set;

public final class CurrencySupportedRule implements ValidationRule {
    private static final String FIELD = "instructedCurrency";

    public static final Set<String> DEFAULT_SUPPORTED_CURRENCIES = Set.of(
            "AED", "AUD", "BHD", "CAD", "CHF", "EUR", "GBP",
            "HKD", "INR", "JPY", "KWD", "SGD", "USD");

    private final Set<String> supportedCurrencies;

    public CurrencySupportedRule() {
        this(DEFAULT_SUPPORTED_CURRENCIES);
    }

    public CurrencySupportedRule(Set<String> supportedCurrencies) {
        this.supportedCurrencies = Set.copyOf(supportedCurrencies);
    }

    @Override
    public RuleId id() {
        return RuleId.R03_CURRENCY_SUPPORTED;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        String currency = request.instructedCurrency();

        if (currency == null || currency.isBlank()) {
            return Optional.of(new RejectionReason(id(), FIELD, "instructedCurrency is required"));
        }
        if (!supportedCurrencies.contains(currency)) {
            return Optional.of(new RejectionReason(
                    id(), FIELD, "instructedCurrency is not a supported settlement currency"));
        }
        return Optional.empty();
    }

    public Set<String> supportedCurrencies() {
        return supportedCurrencies;
    }

    public static Currency parse(String code) {
        return Currency.getInstance(code);
    }
}
