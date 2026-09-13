package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R04_END_TO_END_ID — endToEndId present, non-empty, within max length.
 *
 * <p>Pick the max length yourself and document it. Blank-versus-empty matters: decide whether a string of spaces is present. This field is also the correlation key for structured logging in M5, so whatever you allow here shows up in every log line.
 */
public final class EndToEndIdRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R04_END_TO_END_ID;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R04_END_TO_END_ID");
    }
}
