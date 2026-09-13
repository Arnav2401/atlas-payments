package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R02 — decimal places must not exceed the currency's minor unit. */
class AmountScaleRuleTest {

    private final AmountScaleRule rule = new AmountScaleRule();

    @Test
    void is_a_semantic_rule_because_it_depends_on_R03() {
        assertEquals(AmountScaleRule.Phase.SEMANTIC, rule.phase());
    }

    @ParameterizedTest
    @CsvSource({"USD,10.50", "USD,10.5", "USD,10", "JPY,100", "BHD,1.234", "EUR,0.01"})
    void accepts_amounts_within_the_currency_minor_unit(String currency, String amount) {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency(currency).instructedAmount(amount).build();

        assertTrue(rule.check(request).isEmpty(), currency + " " + amount + " should be accepted");
    }

    @ParameterizedTest
    @CsvSource({"JPY,100.50", "USD,10.123", "BHD,1.2345"})
    void rejects_amounts_with_too_many_decimal_places(String currency, String amount) {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency(currency).instructedAmount(amount).build();

        assertTrue(rule.check(request).isPresent(), currency + " " + amount + " should be rejected");
    }

    /**
     * Edge: 10 and 10.00 are equal in value and differ in scale. Both are within
     * USD's two decimal places, so both pass — this pins that the rule compares
     * scale against a ceiling rather than demanding an exact match.
     */
    @Test
    void edge_equal_values_with_different_scale_are_both_accepted_for_usd() {
        assertTrue(rule.check(PaymentInstructionRequests.valid()
                .instructedCurrency("USD").instructedAmount("10").build()).isEmpty());
        assertTrue(rule.check(PaymentInstructionRequests.valid()
                .instructedCurrency("USD").instructedAmount("10.00").build()).isEmpty());
    }

    /**
     * Edge: documents the decision NOT to stripTrailingZeros() first. 100.000 USD
     * has a representable value but asks for precision USD does not have.
     */
    @Test
    void edge_trailing_zeros_beyond_the_minor_unit_are_rejected_not_stripped() {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency("USD").instructedAmount("100.000").build();

        assertTrue(rule.check(request).isPresent(),
                "100.000 USD is rejected: precision is part of the instruction");
    }

    /** Edge: negative scale. 1E+2 is a whole number and must not be mistaken for fractional. */
    @Test
    void edge_negative_scale_is_accepted() {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency("JPY").instructedAmount("1E+2").build();

        assertTrue(rule.check(request).isEmpty());
    }

    /** This rule must stay silent about failures that belong to R01 and R03. */
    @Test
    void defers_to_the_rule_that_owns_the_failure() {
        assertTrue(rule.check(PaymentInstructionRequests.valid()
                .instructedAmount((java.math.BigDecimal) null).build()).isEmpty(), "R01 owns a missing amount");
        assertTrue(rule.check(PaymentInstructionRequests.valid()
                .instructedCurrency("XYZ").build()).isEmpty(), "R03 owns an unknown currency");
    }

    @Test
    void reports_its_own_rule_id() {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency("JPY").instructedAmount("1.50").build();

        assertEquals(RuleId.R02_AMOUNT_SCALE_MATCHES_CURRENCY, rule.check(request).orElseThrow().ruleId());
    }
}
