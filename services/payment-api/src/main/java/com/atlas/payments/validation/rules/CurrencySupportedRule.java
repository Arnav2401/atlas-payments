package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Currency;
import java.util.Optional;
import java.util.Set;

/**
 * R03 — the instructed currency must be one this service settles.
 *
 * <h2>Why this is not a JDK lookup</h2>
 *
 * <p>The obvious implementation is {@code Currency.getAvailableCurrencies()}
 * or {@code Currency.getInstance(code)}. Both are wrong for this rule, and the
 * evidence is in {@code CurrencySupportedRuleTest}: that set has 233 entries on
 * JDK 21 and contains {@code DEM}, {@code FRF} and {@code ZWD} — currencies that
 * have not existed for years. It also contains the ISO 4217 pseudo-codes
 * {@code XXX} ("no currency") and {@code XAU} (gold), which report a minor unit
 * of -1 and would break R02 downstream.
 *
 * <p>{@code java.util.Currency} is bundled data describing "codes ISO has ever
 * assigned", tracking the JDK version rather than any live feed. It cannot
 * express "live", so it cannot implement this rule alone.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>An explicit allow-list, not the JDK set.</b> A payment service settles
 * the corridors it has arrangements for, which is always a small subset of
 * ISO 4217 — so "supported" is the honest predicate and "live" is a weaker one
 * it implies. The list is injectable so M5 can move it to configuration; the
 * no-arg constructor keeps {@code ValidationConfig} readable today. A test
 * asserts every entry is a real ISO code, so a typo here fails the build rather
 * than silently rejecting good payments.
 *
 * <p><b>Lowercase is rejected, not normalised.</b> ISO 4217 codes are uppercase
 * by definition. Postel's law argues for {@code toUpperCase()} and it is a
 * defensible reading — but normalising at the boundary means every downstream
 * comparison, ledger row and reconciliation report has to agree on where
 * normalisation happened, and in payments that class of ambiguity is how you get
 * two systems that disagree about whether they hold the same currency. Strict in
 * equals strict out. Note the JDK agrees: {@code Currency.getInstance("usd")}
 * throws.
 */
public final class CurrencySupportedRule implements ValidationRule {

    private static final String FIELD = "instructedCurrency";

    /**
     * The currencies this service settles.
     *
     * <p>Deliberately spans all three minor-unit shapes so R02 has real cases to
     * work against: JPY has 0 decimal places, most have 2, and BHD and KWD have 3.
     */
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

    /** Exposed for R02, which needs the minor unit, and for the README table. */
    public Set<String> supportedCurrencies() {
        return supportedCurrencies;
    }

    /** Convenience for tests and for R02: the parsed currency, which R03 has proved exists. */
    public static Currency parse(String code) {
        return Currency.getInstance(code);
    }
}
