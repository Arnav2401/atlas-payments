package com.atlas.payments.validation.rules;

import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.RuleId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementDateWindowRuleTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final int WINDOW = 30;

    private final Clock fixedClock =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private final SettlementDateWindowRule rule = new SettlementDateWindowRule(fixedClock, WINDOW);

    @Test
    void is_a_semantic_rule() {
        assertEquals(SettlementDateWindowRule.Phase.SEMANTIC, rule.phase());
    }

    @Test
    void accepts_a_date_inside_the_window() {
        var request = PaymentInstructionRequests.valid().settlementDate(TODAY.plusDays(1)).build();

        assertTrue(rule.check(request).isEmpty());
    }

    @Test
    void rejects_a_date_in_the_past() {
        var request = PaymentInstructionRequests.valid().settlementDate(TODAY.minusDays(1)).build();

        assertEquals(RuleId.R08_SETTLEMENT_DATE_WINDOW, rule.check(request).orElseThrow().ruleId());
    }

    @Test
    void rejects_a_date_beyond_the_window() {
        var request = PaymentInstructionRequests.valid()
                .settlementDate(TODAY.plusDays(WINDOW + 1)).build();

        assertTrue(rule.check(request).isPresent());
    }

    @Test
    void edge_both_boundaries_are_inclusive() {
        assertTrue(rule.check(PaymentInstructionRequests.valid()
                        .settlementDate(TODAY).build()).isEmpty(),
                "today must be accepted - same-day settlement");
        assertTrue(rule.check(PaymentInstructionRequests.valid()
                        .settlementDate(TODAY.plusDays(WINDOW)).build()).isEmpty(),
                "exactly N days forward must be accepted");
    }

    @Test
    void edge_today_is_evaluated_in_utc_not_the_clock_zone() {
        Clock tokyo = Clock.fixed(
                Instant.parse("2026-09-14T17:30:00Z"), ZoneId.of("Asia/Tokyo")); // 15th, 02:30 local
        var ruleInTokyo = new SettlementDateWindowRule(tokyo, WINDOW);

        var request = PaymentInstructionRequests.valid().settlementDate(TODAY).build();

        assertTrue(ruleInTokyo.check(request).isEmpty(),
                "still the 14th in UTC, so the 14th is not in the past");
    }

    @Test
    void rejects_an_absent_date() {
        var request = PaymentInstructionRequests.valid().settlementDate(null).build();

        assertTrue(rule.check(request).isPresent());
    }
}
