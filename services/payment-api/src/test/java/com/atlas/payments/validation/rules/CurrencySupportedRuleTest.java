package com.atlas.payments.validation.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * R03_CURRENCY_SUPPORTED.
 *
 * <p>Brief requires a valid case, an invalid case and an edge case per rule.
 * These fail rather than being @Disabled on purpose: a disabled test is a green
 * build that proves nothing, and M1 is not done until all thirty pass.
 */
class CurrencySupportedRuleTest {

    @Test
    void accepts_a_conforming_request() {
        fail("TODO(M1): USD passes");
    }

    @Test
    void rejects_a_violating_request() {
        fail("TODO(M1): XYZ is rejected");
    }

    @Test
    void edge_case() {
        fail("TODO(M1): lowercase usd - decide whether you normalise or reject");
    }
}
