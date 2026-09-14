package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

public final class SettlementDateWindowRule implements ValidationRule {
    private static final String FIELD = "settlementDate";

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
