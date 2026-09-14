package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountsRuleTest {
    private static final String ACCOUNT = "DE89370400440532013000";

    private final AccountsRule rule = new AccountsRule();

    @Test
    void accepts_two_distinct_accounts() {
        assertTrue(rule.check(PaymentInstructionRequests.valid().build()).isEmpty());
    }

    @Test
    void rejects_a_payment_from_an_account_to_itself() {
        var request = PaymentInstructionRequests.valid()
                .debtorAccount(ACCOUNT).creditorAccount(ACCOUNT).build();

        var reason = rule.check(request).orElseThrow();

        assertEquals(RuleId.R06_ACCOUNTS_PRESENT_AND_DISTINCT, reason.ruleId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"de89370400440532013000", "  DE89370400440532013000  ", "De89370400440532013000"})
    void edge_self_payment_is_caught_despite_case_or_whitespace(String disguised) {
        var request = PaymentInstructionRequests.valid()
                .debtorAccount(ACCOUNT).creditorAccount(disguised).build();

        assertTrue(rule.check(request).isPresent(), "should be caught: " + disguised);
    }

    @Test
    void rejects_absent_accounts_and_names_the_field() {
        assertEquals("debtorAccount",
                rule.check(PaymentInstructionRequests.valid().debtorAccount(null).build())
                        .orElseThrow().field());
        assertEquals("creditorAccount",
                rule.check(PaymentInstructionRequests.valid().creditorAccount("   ").build())
                        .orElseThrow().field());
    }
}
