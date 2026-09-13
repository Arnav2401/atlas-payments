package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;

/**
 * R02 — decimal places must not exceed the currency's minor unit.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>{@code scale() <= minorUnits}, not {@code ==}.</b> "100" is a valid USD
 * amount; requiring exactly two decimal places would reject it. Note this also
 * handles negative scale correctly — {@code new BigDecimal("1E+2")} has scale
 * -2 and is a whole number.
 *
 * <p><b>No {@code stripTrailingZeros()} first.</b> {@code 100.000} USD is
 * therefore rejected, even though its value is representable. That is
 * deliberate: the precision of the instruction is part of the instruction, and
 * a sender asking for three decimal places of USD has a currency model that
 * disagrees with ours. Catching that at the boundary is the point; silently
 * truncating it is how you get a reconciliation break. It is also consistent
 * with R03 refusing to normalise. If you would rather be lenient, strip first —
 * but then say so in the README, because the two rules would no longer agree
 * about how forgiving this API is.
 *
 * <p><b>This rule is SEMANTIC and depends on R03.</b> It asks the currency for
 * its minor unit, which is meaningless if R03 already rejected the currency.
 * The validator guarantees the ordering, but this class still defends itself:
 * if the amount or currency is unusable it returns empty and lets the rule that
 * owns that failure report it, rather than emitting a second confusing reason.
 */
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

        // Not our failure to report — R01 owns a missing amount, R03 the currency.
        if (amount == null || currency == null) {
            return Optional.empty();
        }

        int minorUnits = currency.getDefaultFractionDigits();
        if (minorUnits < 0) {
            // A pseudo-currency such as XXX or XAU. R03's allow-list excludes
            // these, so reaching here means the allow-list was widened without
            // revisiting this rule.
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
