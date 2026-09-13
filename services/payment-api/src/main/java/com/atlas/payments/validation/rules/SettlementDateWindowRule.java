package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * R08 — settlement date not in the past, not more than N days forward.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>The clock is injected.</b> This is the whole reason this rule is worth
 * discussing. {@code LocalDate.now()} inside the method makes the rule
 * untestable without freezing system time, and makes the boundary cases — today,
 * and exactly N days out — impossible to assert reliably. An injected
 * {@link Clock} turns "not in the past" into a pure function of its inputs.
 *
 * <p><b>UTC, explicitly.</b> "Today" is timezone-dependent, so a rule that says
 * "not in the past" without naming a zone is ambiguous by construction: the same
 * payment is valid in Mumbai and rejected in New York for eleven and a half
 * hours a day. UTC is the defensible simple answer and it is stated rather than
 * inherited from whatever the server's default zone happens to be.
 *
 * <p><b>Known limit:</b> a real settlement system uses a business-date calendar —
 * currency holidays, cut-off times, and the fact that a Saturday is not a
 * settlement day. This rule does not model any of that, and the README should
 * not imply it does. It is a window check, not a calendar.
 *
 * <p><b>Both boundaries inclusive.</b> Today is acceptable (same-day settlement)
 * and exactly N days forward is acceptable. Stated because "not more than N days
 * forward" is precisely the kind of phrasing that produces an off-by-one nobody
 * notices until a customer hits it on day N.
 *
 * <p><b>This rule is SEMANTIC</b> because it is a business-policy question
 * rather than a question about the shape of the payload.
 */
public final class SettlementDateWindowRule implements ValidationRule {

    private static final String FIELD = "settlementDate";

    /** Default forward window. A policy number — move it to configuration in M5. */
    public static final int DEFAULT_MAX_DAYS_FORWARD = 30;

    private final Clock clock;
    private final int maxDaysForward;

    public SettlementDateWindowRule() {
        this(Clock.systemUTC(), DEFAULT_MAX_DAYS_FORWARD);
    }

    public SettlementDateWindowRule(Clock clock, int maxDaysForward) {
        this.clock = clock;
        this.maxDaysForward = maxDaysForward;
    }

    @Override
    public RuleId id() {
        return RuleId.R08_SETTLEMENT_DATE_WINDOW;
    }

    @Override
    public Phase phase() {
        return Phase.SEMANTIC;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        LocalDate settlementDate = request.settlementDate();

        if (settlementDate == null) {
            return Optional.of(new RejectionReason(id(), FIELD, "settlementDate is required"));
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate latest = today.plusDays(maxDaysForward);

        if (settlementDate.isBefore(today)) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "settlementDate is in the past (today is " + today + " UTC)"));
        }
        if (settlementDate.isAfter(latest)) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "settlementDate is more than " + maxDaysForward + " days forward"));
        }
        return Optional.empty();
    }
}
