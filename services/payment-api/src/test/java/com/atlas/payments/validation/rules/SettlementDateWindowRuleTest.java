package com.atlas.payments.validation.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * R08_SETTLEMENT_DATE_WINDOW.
 *
 * <p>Brief requires a valid case, an invalid case and an edge case per rule.
 * These fail rather than being @Disabled on purpose: a disabled test is a green
 * build that proves nothing, and M1 is not done until all thirty pass.
 */
class SettlementDateWindowRuleTest {

    @Test
    void accepts_a_conforming_request() {
        fail("TODO(M1): tomorrow passes, against a fixed Clock");
    }

    @Test
    void rejects_a_violating_request() {
        fail("TODO(M1): yesterday is rejected");
    }

    @Test
    void edge_case() {
        fail("TODO(M1): today, and exactly N days forward - both boundaries, inclusive or exclusive");
    }
}
