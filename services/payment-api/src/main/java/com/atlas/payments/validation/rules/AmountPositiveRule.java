package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * R01 — the instructed amount must be strictly positive.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>An absent amount fails R01, not R10.</b> "Strictly positive" is
 * unsatisfiable by null, and the alternative — letting null through to be caught
 * by the bounds rule — means R02 would run against a null amount first and
 * produce a misleading second rejection. One rule owning both "missing" and
 * "not positive" keeps the caller's error list honest. The cost is that R01's
 * reason code covers two distinct causes; if you would rather they were
 * separable on the wire, that is an argument for two codes under one rule, and
 * {@link RejectionReason#message()} is currently carrying that distinction.
 *
 * <p><b>{@code signum()} rather than {@code compareTo(BigDecimal.ZERO)}.</b>
 * They agree here, but signum states the intent — and the reason it is safe is
 * scale: {@code new BigDecimal("0.00").signum()} is 0, as is {@code "-0.00"}.
 * {@code equals(BigDecimal.ZERO)} would have been the bug, since {@code 0.00}
 * is not {@code equals} to {@code 0} — same value, different scale.
 */
public final class AmountPositiveRule implements ValidationRule {

    private static final String FIELD = "instructedAmount";

    @Override
    public RuleId id() {
        return RuleId.R01_AMOUNT_POSITIVE;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        BigDecimal amount = request.instructedAmount();

        if (amount == null) {
            return Optional.of(new RejectionReason(id(), FIELD, "instructedAmount is required"));
        }
        if (amount.signum() <= 0) {
            return Optional.of(new RejectionReason(
                    id(), FIELD, "instructedAmount must be strictly greater than zero"));
        }
        return Optional.empty();
    }
}
