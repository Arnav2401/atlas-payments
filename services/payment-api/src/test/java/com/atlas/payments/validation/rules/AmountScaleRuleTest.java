package com.atlas.payments.validation.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * R02_AMOUNT_SCALE_MATCHES_CURRENCY.
 *
 * <p>Brief requires a valid case, an invalid case and an edge case per rule.
 * These fail rather than being @Disabled on purpose: a disabled test is a green
 * build that proves nothing, and M1 is not done until all thirty pass.
 */
class AmountScaleRuleTest {

    @Test
    void accepts_a_conforming_request() {
        fail("TODO(M1): USD 10.50 passes");
    }

    @Test
    void rejects_a_violating_request() {
        fail("TODO(M1): JPY 100.50 is rejected - JPY has no minor unit");
    }

    @Test
    void edge_case() {
        fail("TODO(M1): USD 10 versus USD 10.00 - equal values, different BigDecimal scale");
    }
}
