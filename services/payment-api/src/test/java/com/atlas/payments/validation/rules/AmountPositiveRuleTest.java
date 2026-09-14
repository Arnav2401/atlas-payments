package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmountPositiveRuleTest {
    private final AmountPositiveRule rule = new AmountPositiveRule();

    @Test
    void accepts_a_positive_amount() {
        var request = PaymentInstructionRequests.valid().instructedAmount("100.00").build();

        assertTrue(rule.check(request).isEmpty());
    }

    @Test
    void rejects_a_negative_amount() {
        var request = PaymentInstructionRequests.valid().instructedAmount("-0.01").build();

        Optional<RejectionReason> reason = rule.check(request);

        assertTrue(reason.isPresent());
        assertEquals(RuleId.R01_AMOUNT_POSITIVE, reason.get().ruleId());
        assertEquals("instructedAmount", reason.get().field());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.00", "0.0000"})
    void edge_every_spelling_of_zero_is_rejected(String amount) {
        var request = PaymentInstructionRequests.valid().instructedAmount(amount).build();

        assertTrue(rule.check(request).isPresent(), amount + " should be rejected");
    }

    @Test
    void edge_an_absent_amount_is_rejected_by_this_rule() {
        var request = PaymentInstructionRequests.valid().instructedAmount((BigDecimal) null).build();

        Optional<RejectionReason> reason = rule.check(request);

        assertTrue(reason.isPresent());
        assertEquals(RuleId.R01_AMOUNT_POSITIVE, reason.get().ruleId());
    }
}
