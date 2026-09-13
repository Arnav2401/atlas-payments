package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R08_SETTLEMENT_DATE_WINDOW — settlement date not in the past, not more than N days forward.
 *
 * <p>Two decisions you have to make and defend. First: N, and where it is configured. Second, and more interesting: which clock, and in which zone? 'Not in the past' is timezone-dependent, and a fixed Clock injected here is also what makes this rule testable without freezing real time.
 */
public final class SettlementDateWindowRule implements ValidationRule {

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
        throw new UnsupportedOperationException("TODO(M1): implement R08_SETTLEMENT_DATE_WINDOW");
    }
}
