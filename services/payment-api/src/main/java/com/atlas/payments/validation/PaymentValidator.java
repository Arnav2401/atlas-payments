package com.atlas.payments.validation;

import com.atlas.payments.api.dto.PaymentInstructionRequest;

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
        throw new UnsupportedOperationException(
                "TODO(M1): implement the two-phase run described in the javadoc above");
    }

    /** Exposed so the README rule table can be generated from the code, not hand-maintained. */
    public List<ValidationRule> rules() {
        return rules;
    }
}
