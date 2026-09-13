package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R04 — endToEndId present, non-blank, bounded, safe to log. */
class EndToEndIdRuleTest {

    private final EndToEndIdRule rule = new EndToEndIdRule();

    @Test
    void accepts_a_conforming_id() {
        var request = PaymentInstructionRequests.valid().endToEndId("INV-2026-0091/AX").build();

        assertTrue(rule.check(request).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void rejects_absent_or_blank_ids(String id) {
        assertTrue(rule.check(PaymentInstructionRequests.valid().endToEndId(id).build()).isPresent());
    }

    @Test
    void rejects_a_null_id() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().endToEndId(null).build()).isPresent());
    }

    /** Edge: exactly at the ISO 20022 bound, and one over. */
    @Test
    void edge_boundary_is_inclusive_at_35_characters() {
        String exactly35 = "A".repeat(EndToEndIdRule.MAX_LENGTH);
        String thirtySix = "A".repeat(EndToEndIdRule.MAX_LENGTH + 1);

        assertTrue(rule.check(PaymentInstructionRequests.valid().endToEndId(exactly35).build()).isEmpty(),
                "35 characters is the limit, not one past it");
        assertTrue(rule.check(PaymentInstructionRequests.valid().endToEndId(thirtySix).build()).isPresent());
    }

    /**
     * Edge, and the security-relevant case: endToEndId is the M5 log correlation
     * key, so a newline in it would let a caller forge log lines.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ABC\nDEF", "ABC\rDEF", "ABC\u0000DEF", "ABC<script>", "ABC;DROP"})
    void edge_rejects_characters_that_would_be_unsafe_in_a_log_line(String id) {
        assertTrue(rule.check(PaymentInstructionRequests.valid().endToEndId(id).build()).isPresent(),
                "must reject: " + id.replace("\n", "\\n").replace("\r", "\\r"));
    }

    @Test
    void reports_its_own_rule_id() {
        var request = PaymentInstructionRequests.valid().endToEndId("").build();

        assertEquals(RuleId.R04_END_TO_END_ID, rule.check(request).orElseThrow().ruleId());
    }
}
