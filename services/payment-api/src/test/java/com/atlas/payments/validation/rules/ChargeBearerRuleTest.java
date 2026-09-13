package com.atlas.payments.validation.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * R07_CHARGE_BEARER_SUPPORTED.
 *
 * <p>Brief requires a valid case, an invalid case and an edge case per rule.
 * These fail rather than being @Disabled on purpose: a disabled test is a green
 * build that proves nothing, and M1 is not done until all thirty pass.
 */
class ChargeBearerRuleTest {

    @Test
    void accepts_a_conforming_request() {
        fail("TODO(M1): SHAR passes");
    }

    @Test
    void rejects_a_violating_request() {
        fail("TODO(M1): BOGUS is rejected");
    }

    @Test
    void edge_case() {
        fail("TODO(M1): lowercase shar - same normalisation question as R03, answer it the same way");
    }
}
