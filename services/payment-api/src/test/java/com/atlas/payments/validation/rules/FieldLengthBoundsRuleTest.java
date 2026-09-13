package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R10 — bound the fields no other rule bounds. */
class FieldLengthBoundsRuleTest {

    private final FieldLengthBoundsRule rule = new FieldLengthBoundsRule();

    @Test
    void accepts_a_normal_request() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().build()).isEmpty());
    }

    @Test
    void rejects_an_over_long_account() {
        String tooLong = "D".repeat(FieldLengthBoundsRule.MAX_ACCOUNT_LENGTH + 1);
        var request = PaymentInstructionRequests.valid().debtorAccount(tooLong).build();

        var reason = rule.check(request).orElseThrow();

        assertEquals(RuleId.R10_FIELD_LENGTH_BOUNDS, reason.ruleId());
        assertEquals("debtorAccount", reason.field());
    }

    /** Edge: exactly at the ISO 13616 IBAN maximum is acceptable; one over is not. */
    @Test
    void edge_boundary_is_inclusive_at_34_characters() {
        String exactly34 = "D".repeat(FieldLengthBoundsRule.MAX_ACCOUNT_LENGTH);
        String thirtyFive = "D".repeat(FieldLengthBoundsRule.MAX_ACCOUNT_LENGTH + 1);

        assertTrue(rule.check(PaymentInstructionRequests.valid().debtorAccount(exactly34).build()).isEmpty());
        assertTrue(rule.check(PaymentInstructionRequests.valid().debtorAccount(thirtyFive).build()).isPresent());
    }

    @Test
    void bounds_the_creditor_account_too() {
        String tooLong = "C".repeat(FieldLengthBoundsRule.MAX_ACCOUNT_LENGTH + 1);
        var request = PaymentInstructionRequests.valid().creditorAccount(tooLong).build();

        assertEquals("creditorAccount", rule.check(request).orElseThrow().field());
    }

    /**
     * Pins the scoping decision: R10 deliberately does not re-check fields that
     * another rule already bounds, so two rules cannot reject the same input with
     * different reason codes. An over-long endToEndId is R04's failure, and this
     * rule must stay silent about it.
     */
    @Test
    void defers_fields_that_another_rule_already_bounds() {
        var request = PaymentInstructionRequests.valid()
                .endToEndId("E".repeat(200))
                .build();

        assertTrue(rule.check(request).isEmpty(), "R04 owns endToEndId length, not R10");
    }

    /** The total-character backstop, which is defence in depth and not a request-size limit. */
    @Test
    void rejects_a_payload_over_the_total_character_backstop() {
        var request = PaymentInstructionRequests.valid()
                .debtorAccount("D".repeat(34))
                .creditorAccount("C".repeat(34))
                .endToEndId("E".repeat(FieldLengthBoundsRule.MAX_TOTAL_CHARACTERS))
                .build();

        assertEquals("*", rule.check(request).orElseThrow().field());
    }
}
