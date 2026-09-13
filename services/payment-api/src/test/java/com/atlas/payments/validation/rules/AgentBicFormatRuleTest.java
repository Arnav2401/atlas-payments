package com.atlas.payments.validation.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * R05_AGENT_BIC_FORMAT.
 *
 * <p>Brief requires a valid case, an invalid case and an edge case per rule.
 * These fail rather than being @Disabled on purpose: a disabled test is a green
 * build that proves nothing, and M1 is not done until all thirty pass.
 */
class AgentBicFormatRuleTest {

    @Test
    void accepts_a_conforming_request() {
        fail("TODO(M1): an 8-character and an 11-character BIC both pass");
    }

    @Test
    void rejects_a_violating_request() {
        fail("TODO(M1): a 9-character BIC is rejected");
    }

    @Test
    void edge_case() {
        fail("TODO(M1): valid debtor BIC with invalid creditor BIC - assert the field names which agent");
    }
}
