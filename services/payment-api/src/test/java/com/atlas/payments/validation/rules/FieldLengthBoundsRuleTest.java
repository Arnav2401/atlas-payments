package com.atlas.payments.validation.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * R10_FIELD_LENGTH_BOUNDS.
 *
 * <p>Brief requires a valid case, an invalid case and an edge case per rule.
 * These fail rather than being @Disabled on purpose: a disabled test is a green
 * build that proves nothing, and M1 is not done until all thirty pass.
 */
class FieldLengthBoundsRuleTest {

    @Test
    void accepts_a_conforming_request() {
        fail("TODO(M1): a normal request passes");
    }

    @Test
    void rejects_a_violating_request() {
        fail("TODO(M1): a field one character over its bound is rejected");
    }

    @Test
    void edge_case() {
        fail("TODO(M1): a field exactly at its bound");
    }
}
