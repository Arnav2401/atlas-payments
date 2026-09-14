package com.atlas.payments.validation;

import com.atlas.payments.api.dto.PaymentInstructionRequest;

import java.util.Optional;

public interface ValidationRule {
    enum Phase {
        STRUCTURAL,
        SEMANTIC
    }

    RuleId id();

    default Phase phase() {
        return Phase.STRUCTURAL;
    }

    Optional<RejectionReason> check(PaymentInstructionRequest request);
}
