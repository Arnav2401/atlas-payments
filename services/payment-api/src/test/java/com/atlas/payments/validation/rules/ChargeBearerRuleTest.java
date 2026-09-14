package com.atlas.payments.validation.rules;

import com.atlas.payments.domain.PaymentInstruction.ChargeBearer;
import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChargeBearerRuleTest {
    private final ChargeBearerRule rule = new ChargeBearerRule();

    @ParameterizedTest
    @ValueSource(strings = {"DEBT", "CRED", "SHAR", "SLEV"})
    void accepts_every_supported_value(String chargeBearer) {
        var request = PaymentInstructionRequests.valid().chargeBearer(chargeBearer).build();

        assertTrue(rule.check(request).isEmpty(), chargeBearer + " should be accepted");
    }

    @Test
    void rejects_an_unsupported_value() {
        var request = PaymentInstructionRequests.valid().chargeBearer("BOGUS").build();

        assertEquals(RuleId.R07_CHARGE_BEARER_SUPPORTED, rule.check(request).orElseThrow().ruleId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"shar", "Shar", "sHaR"})
    void edge_case_variants_are_rejected_not_normalised(String chargeBearer) {
        var request = PaymentInstructionRequests.valid().chargeBearer(chargeBearer).build();

        assertTrue(rule.check(request).isPresent(), chargeBearer + " should be rejected");
    }

    @Test
    void rejects_absent_or_blank() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().chargeBearer(null).build()).isPresent());
        assertTrue(rule.check(PaymentInstructionRequests.valid().chargeBearer(" ").build()).isPresent());
    }

    @Test
    void the_supported_set_is_derived_from_the_domain_enum() {
        for (ChargeBearer value : ChargeBearer.values()) {
            var request = PaymentInstructionRequests.valid().chargeBearer(value.name()).build();
            assertTrue(rule.check(request).isEmpty(), value + " must be accepted");
        }
    }
}
