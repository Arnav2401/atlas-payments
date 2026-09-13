package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Currency;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R03 — instructed currency must be one this service settles. */
class CurrencySupportedRuleTest {

    private final CurrencySupportedRule rule = new CurrencySupportedRule();

    @Test
    void accepts_a_supported_currency() {
        var request = PaymentInstructionRequests.valid().instructedCurrency("USD").build();

        assertTrue(rule.check(request).isEmpty());
    }

    @Test
    void rejects_a_code_that_is_not_a_currency() {
        var request = PaymentInstructionRequests.valid().instructedCurrency("XYZ").build();

        Optional<RejectionReason> reason = rule.check(request);

        assertTrue(reason.isPresent());
        assertEquals(RuleId.R03_CURRENCY_SUPPORTED, reason.get().ruleId());
        assertEquals("instructedCurrency", reason.get().field());
    }

    /**
     * Edge: lowercase is rejected, not normalised. If you decide to normalise
     * instead, this test is the one that should fail and force the decision to
     * be made deliberately rather than by a stray toUpperCase().
     */
    @ParameterizedTest
    @ValueSource(strings = {"usd", "Usd", "uSD"})
    void edge_case_variants_are_rejected_not_normalised(String currency) {
        var request = PaymentInstructionRequests.valid().instructedCurrency(currency).build();

        assertTrue(rule.check(request).isPresent(), currency + " should be rejected");
    }

    @Test
    void edge_an_absent_or_blank_currency_is_rejected() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().instructedCurrency(null).build()).isPresent());
        assertTrue(rule.check(PaymentInstructionRequests.valid().instructedCurrency("   ").build()).isPresent());
    }

    /**
     * Edge, and the reason this rule is not a one-line JDK lookup: DEM has not
     * been a currency since 2002, but the JDK still knows it.
     */
    @Test
    void edge_a_withdrawn_currency_is_rejected_even_though_the_jdk_knows_it() {
        var request = PaymentInstructionRequests.valid().instructedCurrency("DEM").build();

        assertTrue(rule.check(request).isPresent(), "DEM must not be settleable");
    }

    /**
     * Executable rationale. This is not testing our code — it pins the JDK
     * behaviour that justifies the allow-list, so that if a future JDK cleans up
     * its currency data, this test fails and tells you the justification has
     * changed rather than leaving a stale comment behind.
     */
    @Test
    void documents_that_the_jdk_currency_list_is_not_a_live_iso_4217_feed() {
        Set<String> jdkCodes = Currency.getAvailableCurrencies().stream()
                .map(Currency::getCurrencyCode)
                .collect(Collectors.toSet());

        assertTrue(jdkCodes.contains("DEM"), "JDK still lists the Deutsche Mark");
        assertTrue(jdkCodes.contains("ZWD"), "JDK still lists the old Zimbabwe dollar");
        assertEquals(-1, Currency.getInstance("XXX").getDefaultFractionDigits(),
                "XXX is a pseudo-currency with no minor unit and would break R02");
    }

    /**
     * A typo in the allow-list would silently reject good payments. Fail the
     * build instead.
     */
    @Test
    void every_supported_currency_is_a_real_iso_4217_code() {
        Set<String> jdkCodes = Currency.getAvailableCurrencies().stream()
                .map(Currency::getCurrencyCode)
                .collect(Collectors.toSet());

        Set<String> unknown = CurrencySupportedRule.DEFAULT_SUPPORTED_CURRENCIES.stream()
                .filter(code -> !jdkCodes.contains(code))
                .collect(Collectors.toSet());

        assertTrue(unknown.isEmpty(), "not real ISO 4217 codes: " + unknown);
    }

    /** R02 depends on the minor unit, so the set must cover more than the 2-decimal case. */
    @Test
    void the_supported_set_spans_every_minor_unit_shape_r02_must_handle() {
        Set<Integer> minorUnits = CurrencySupportedRule.DEFAULT_SUPPORTED_CURRENCIES.stream()
                .map(Currency::getInstance)
                .map(Currency::getDefaultFractionDigits)
                .collect(Collectors.toSet());

        assertTrue(minorUnits.containsAll(Set.of(0, 2, 3)),
                "expected 0- 2- and 3-decimal currencies, found " + minorUnits);
        assertFalse(minorUnits.contains(-1), "no pseudo-currencies in the supported set");
    }
}
