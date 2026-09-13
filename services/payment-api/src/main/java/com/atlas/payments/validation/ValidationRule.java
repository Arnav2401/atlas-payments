package com.atlas.payments.validation;

import com.atlas.payments.api.dto.PaymentInstructionRequest;

import java.util.Optional;

/**
 * One validation rule.
 *
 * <p>One implementation per rule, one test class per rule, one row per rule in
 * the README. The alternative — a validation chain where each link calls the
 * next — buys explicit ordering but costs per-rule testability, since testing
 * link seven means assembling the chain. Chain-of-responsibility also means
 * "one handler handles it", and validation wants "every applicable rule runs".
 *
 * <p>Note this package imports nothing from Spring. Every rule is testable with
 * a plain JUnit test and no application context, and the rule set survives being
 * moved behind Kafka in M4 without edits.
 */
public interface ValidationRule {

    /**
     * When a rule runs.
     *
     * <p>This is the flat list's answer to the dependency problem: R02 (decimal
     * places match the currency) is meaningless if R03 (currency is a live
     * ISO 4217 code) already failed, and running it anyway emits a second,
     * confusing rejection. Two phases is the cheapest mechanism that fixes it.
     *
     * <p>The general form is each rule declaring {@code Set<RuleId> requires()}
     * and the validator skipping rules whose prerequisites failed. For ten rules
     * that is over-built — but it is the right answer to "what if you had two
     * hundred rules", so know that you rejected it on purpose.
     */
    enum Phase {
        /** Shape, format and code-set membership. Assumes nothing. */
        STRUCTURAL,
        /** Business meaning. Runs only if every STRUCTURAL rule passed. */
        SEMANTIC
    }

    RuleId id();

    default Phase phase() {
        return Phase.STRUCTURAL;
    }

    /**
     * @return empty if the request satisfies this rule, otherwise the reason it
     *         does not. Returning the reason rather than a boolean keeps the
     *         rule's knowledge of its own failure in one place.
     */
    Optional<RejectionReason> check(PaymentInstructionRequest request);
}
