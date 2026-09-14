package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebtorCountryRuleTest {
    private final DebtorCountryRule rule = new DebtorCountryRule();

    @ParameterizedTest
    @ValueSource(strings = {"IN", "DE", "GB", "US", "SG"})
    void accepts_valid_alpha_2_codes(String country) {
        var request = PaymentInstructionRequests.valid().debtorCountry(country).build();

        assertTrue(rule.check(request).isEmpty(), country + " should be accepted");
    }

    @Test
    void rejects_a_code_that_is_not_a_country() {
        var request = PaymentInstructionRequests.valid().debtorCountry("ZZ").build();

        assertEquals(RuleId.R09_DEBTOR_COUNTRY, rule.check(request).orElseThrow().ruleId());
    }

    @Test
    void edge_uk_is_rejected_and_gb_is_accepted() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().debtorCountry("UK").build()).isPresent(),
                "UK is not an ISO 3166-1 alpha-2 code");
        assertTrue(rule.check(PaymentInstructionRequests.valid().debtorCountry("GB").build()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"in", "De", "gB"})
    void edge_case_variants_are_rejected_not_normalised(String country) {
        var request = PaymentInstructionRequests.valid().debtorCountry(country).build();

        assertTrue(rule.check(request).isPresent(), country + " should be rejected");
    }

    @Test
    void rejects_absent_or_blank() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().debtorCountry(null).build()).isPresent());
        assertTrue(rule.check(PaymentInstructionRequests.valid().debtorCountry(" ").build()).isPresent());
    }
}
