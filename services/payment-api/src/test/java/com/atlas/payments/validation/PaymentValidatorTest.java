package com.atlas.payments.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The orchestration behaviour, which no individual rule test covers.
 *
 * <p>This is the class that proves the two-phase design actually does what it
 * claims. If you drop it, the phase mechanism is untested and you will not
 * notice when a refactor collapses it back to a flat list.
 */
class PaymentValidatorTest {

    @Test
    void collects_every_structural_failure_rather_than_failing_fast() {
        fail("TODO(M1): a request violating three structural rules yields three reasons");
    }

    @Test
    void skips_semantic_rules_when_a_structural_rule_failed() {
        fail("TODO(M1): an unsupported currency must not also produce an R02 scale rejection");
    }

    @Test
    void reports_reasons_in_rule_registration_order() {
        fail("TODO(M1): responses must be deterministic, or assertions get flaky");
    }

    @Test
    void produces_a_narrowed_domain_object_when_every_rule_passes() {
        fail("TODO(M1): Accepted carries a PaymentInstruction with a parsed Currency");
    }
}
