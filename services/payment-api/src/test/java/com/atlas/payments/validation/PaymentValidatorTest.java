package com.atlas.payments.validation;

import com.atlas.payments.domain.PaymentInstruction;
import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.rules.AmountPositiveRule;
import com.atlas.payments.validation.rules.AmountScaleRule;
import com.atlas.payments.validation.rules.CurrencySupportedRule;
import com.atlas.payments.validation.rules.DebtorCountryRule;
import com.atlas.payments.validation.rules.EndToEndIdRule;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentValidatorTest {
    private final PaymentValidator validator = new PaymentValidator(List.of(
            new AmountPositiveRule(),       // structural
            new CurrencySupportedRule(),    // structural
            new EndToEndIdRule(),           // structural
            new DebtorCountryRule(),        // structural
            new AmountScaleRule()));        // semantic

    @Test
    void collects_every_structural_failure_rather_than_failing_fast() {
        var request = PaymentInstructionRequests.valid()
                .instructedAmount("-1")
                .endToEndId("")
                .debtorCountry("ZZ")
                .build();

        var rejected = assertInstanceOf(ValidationOutcome.Rejected.class, validator.validate(request));

        assertEquals(
                List.of(RuleId.R01_AMOUNT_POSITIVE, RuleId.R04_END_TO_END_ID, RuleId.R09_DEBTOR_COUNTRY),
                rejected.reasons().stream().map(RejectionReason::ruleId).toList());
    }

    @Test
    void skips_semantic_rules_when_a_structural_rule_failed() {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency("XYZ")
                .instructedAmount("100.50")
                .build();

        var rejected = assertInstanceOf(ValidationOutcome.Rejected.class, validator.validate(request));

        assertEquals(List.of(RuleId.R03_CURRENCY_SUPPORTED),
                rejected.reasons().stream().map(RejectionReason::ruleId).toList());
    }

    @Test
    void runs_semantic_rules_when_every_structural_rule_passed() {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency("JPY")
                .instructedAmount("100.50")
                .build();

        var rejected = assertInstanceOf(ValidationOutcome.Rejected.class, validator.validate(request));

        assertEquals(List.of(RuleId.R02_AMOUNT_SCALE_MATCHES_CURRENCY),
                rejected.reasons().stream().map(RejectionReason::ruleId).toList());
    }

    @Test
    void reports_reasons_in_rule_registration_order() {
        var reversed = new PaymentValidator(List.of(
                new DebtorCountryRule(), new EndToEndIdRule(), new AmountPositiveRule()));

        var request = PaymentInstructionRequests.valid()
                .instructedAmount("-1").endToEndId("").debtorCountry("ZZ").build();

        var rejected = assertInstanceOf(ValidationOutcome.Rejected.class, reversed.validate(request));

        assertEquals(
                List.of(RuleId.R09_DEBTOR_COUNTRY, RuleId.R04_END_TO_END_ID, RuleId.R01_AMOUNT_POSITIVE),
                rejected.reasons().stream().map(RejectionReason::ruleId).toList());
    }

    @Test
    void produces_a_narrowed_domain_object_when_every_rule_passes() {
        var request = PaymentInstructionRequests.valid()
                .instructedCurrency("INR").chargeBearer("DEBT").build();

        var accepted = assertInstanceOf(ValidationOutcome.Accepted.class, validator.validate(request));
        PaymentInstruction instruction = accepted.instruction();

        assertEquals(Currency.getInstance("INR"), instruction.instructedCurrency());
        assertEquals(PaymentInstruction.ChargeBearer.DEBT, instruction.chargeBearer());
    }

    @Test
    void rejection_reasons_are_immutable() {
        var request = PaymentInstructionRequests.valid().instructedAmount("-1").build();
        var rejected = assertInstanceOf(ValidationOutcome.Rejected.class, validator.validate(request));

        assertTrue(rejected.reasons().getClass().getName().contains("Immutable")
                        || isUnmodifiable(rejected.reasons()),
                "reasons must not be mutable by the caller");
    }

    private static boolean isUnmodifiable(List<RejectionReason> reasons) {
        try {
            reasons.add(null);
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }
}
