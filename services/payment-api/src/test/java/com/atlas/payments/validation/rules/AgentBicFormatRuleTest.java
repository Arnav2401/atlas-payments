package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R05 — agent BICs must match ISO 9362. */
class AgentBicFormatRuleTest {

    private final AgentBicFormatRule rule = new AgentBicFormatRule();

    @ParameterizedTest
    @ValueSource(strings = {"DEUTDEFF", "CHASUS33XXX", "HSBCGB2L", "SBININBB104"})
    void accepts_valid_8_and_11_character_bics(String bic) {
        var request = PaymentInstructionRequests.valid().debtorAgent(bic).build();

        assertTrue(rule.check(request).isEmpty(), bic + " should be accepted");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DEUTDEF",      // 7
            "DEUTDEFFX",    // 9 - the classic trap
            "DEUTDEFFXX",   // 10
            "DEUTDEFFXXXX", // 12
            "1234DE5X",     // 8 alphanumeric but institution segment is not alphabetic
            "DEUT2EFF",     // country segment is not alphabetic
            "deutdeff"      // lowercase
    })
    void rejects_malformed_bics(String bic) {
        var request = PaymentInstructionRequests.valid().debtorAgent(bic).build();

        assertTrue(rule.check(request).isPresent(), bic + " should be rejected");
    }

    /**
     * Edge: the rule covers two fields, so a failure must say which agent.
     * This is the test that would catch the rule reporting the wrong field.
     */
    @Test
    void edge_names_the_offending_agent() {
        var request = PaymentInstructionRequests.valid()
                .debtorAgent("DEUTDEFF")
                .creditorAgent("BAD")
                .build();

        RejectionReason reason = rule.check(request).orElseThrow();

        assertEquals(RuleId.R05_AGENT_BIC_FORMAT, reason.ruleId());
        assertEquals("creditorAgent", reason.field());
    }

    /**
     * Edge, and a documented cost: when both agents are malformed only the
     * debtor is reported, so fixing the request takes two round trips. If you
     * split this into two RuleIds, this test should change.
     */
    @Test
    void edge_reports_only_the_first_failure_when_both_agents_are_malformed() {
        var request = PaymentInstructionRequests.valid()
                .debtorAgent("BAD").creditorAgent("ALSOBAD").build();

        assertEquals("debtorAgent", rule.check(request).orElseThrow().field());
    }

    @Test
    void rejects_an_absent_agent() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().debtorAgent(null).build()).isPresent());
        assertTrue(rule.check(PaymentInstructionRequests.valid().creditorAgent("  ").build()).isPresent());
    }
}
