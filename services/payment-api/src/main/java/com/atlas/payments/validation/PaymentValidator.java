package com.atlas.payments.validation;

import com.atlas.payments.api.dto.PaymentInstructionRequest;

import com.atlas.payments.domain.PaymentInstruction;

import java.util.List;

/**
 * Runs the rule set and, on success, produces the validated domain object.
 *
 * <p>The rule list is injected rather than classpath-scanned. The registration
 * list in {@code ValidationConfig} is therefore the authoritative, ordered
 * definition of the rule set — you can read it in one place, and you can swap it
 * wholesale to version the rules.
 */
public final class PaymentValidator {

    private final List<ValidationRule> rules;

    public PaymentValidator(List<ValidationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Contract to implement:
     *
     * <ol>
     *   <li>Run every {@link ValidationRule.Phase#STRUCTURAL} rule and collect
     *       <em>all</em> failures — do not fail fast. A caller with a malformed
     *       payload should learn everything wrong in one round trip, and the
     *       brief requires each of the ten rules to be individually triggerable.</li>
     *   <li>If any structural rule failed, return {@link ValidationOutcome.Rejected}
     *       now. Semantic rules assume structural validity and will produce
     *       misleading errors otherwise.</li>
     *   <li>Otherwise run every {@link ValidationRule.Phase#SEMANTIC} rule,
     *       collecting all failures.</li>
     *   <li>If everything passed, build the {@link com.atlas.payments.domain.PaymentInstruction}
     *       and return {@link ValidationOutcome.Accepted}.</li>
     * </ol>
     *
     * <p>Rejection order should follow rule registration order, not collection
     * order, so responses are deterministic and testable.
     */
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

    /**
     * Runs every rule in one phase and collects all failures. Streaming over the
     * registration list is what makes rejection order deterministic — an
     * unordered collection here would make response assertions flaky.
     */
    private List<RejectionReason> run(PaymentInstructionRequest request, ValidationRule.Phase phase) {
        return rules.stream()
                .filter(rule -> rule.phase() == phase)
                .map(rule -> rule.check(request))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    /** Safe only because every rule passed. See PaymentInstruction#of. */
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

    /** Exposed so the README rule table can be generated from the code, not hand-maintained. */
    public List<ValidationRule> rules() {
        return rules;
    }
}
