package com.atlas.payments.validation;

import com.atlas.payments.api.dto.PaymentInstructionRequest;

import com.atlas.payments.domain.PaymentInstruction;

import java.util.List;

public final class PaymentValidator {
    private final List<ValidationRule> rules;

    public PaymentValidator(List<ValidationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public ValidationOutcome validate(PaymentInstructionRequest request) {
        List<RejectionReason> structural = run(request, ValidationRule.Phase.STRUCTURAL);
        if (!structural.isEmpty()) {
            return new ValidationOutcome.Rejected(structural);
        }

        List<RejectionReason> semantic = run(request, ValidationRule.Phase.SEMANTIC);
        if (!semantic.isEmpty()) {
            return new ValidationOutcome.Rejected(semantic);
        }

        return new ValidationOutcome.Accepted(narrow(request));
    }

    private List<RejectionReason> run(PaymentInstructionRequest request, ValidationRule.Phase phase) {
        return rules.stream()
                .filter(rule -> rule.phase() == phase)
                .map(rule -> rule.check(request))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private static PaymentInstruction narrow(PaymentInstructionRequest request) {
        return PaymentInstruction.of(
                request.endToEndId(),
                request.instructedAmount(),
                request.instructedCurrency(),
                request.debtorAgent(),
                request.creditorAgent(),
                request.debtorAccount(),
                request.creditorAccount(),
                request.debtorCountry(),
                request.chargeBearer(),
                request.settlementDate());
    }

    public List<ValidationRule> rules() {
        return rules;
    }
}
