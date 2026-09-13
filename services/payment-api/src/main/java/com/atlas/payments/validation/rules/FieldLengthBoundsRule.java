package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * R10 — bound the fields that no other rule bounds.
 *
 * <h2>What this rule is NOT</h2>
 *
 * <p>Writing this rule surfaced that it mostly has nothing to do. Every other
 * field is already length-constrained as a side effect of its own format rule:
 * R03 pins currency to a three-character allow-list, R04 caps {@code endToEndId}
 * at 35, R05's BIC pattern admits only 8 or 11 characters, R07 admits four
 * literal values, R09 admits two. Re-checking those here would mean two rules
 * can reject the same input with different reason codes, and the caller cannot
 * tell which contract actually governs the field.
 *
 * <p>So this rule owns exactly what is left: the two free-form account
 * identifiers, plus a total-size backstop. If you later add a free-text field —
 * remittance information is the obvious one — it belongs here.
 *
 * <h2>The thing this rule genuinely cannot do</h2>
 *
 * <p>It cannot protect you from a large request body. By the time it runs,
 * Jackson has already read and materialised the payload; a 40MB body has
 * already been parsed into memory. Request-size limiting belongs upstream, at
 * the container: {@code server.max-http-request-header-size} for headers and
 * {@code server.tomcat.max-swallow-size} plus a filter for bodies. The
 * {@code MAX_TOTAL_CHARACTERS} check below is defence in depth against an
 * oversized-but-parsed payload, not a substitute for that. Say so plainly in the
 * README — a "field lengths bounded" row implies a protection this does not give.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>Account max 34</b>, from ISO 13616's maximum IBAN length. Accounts are
 * not constrained to IBANs here, so this is a bound rather than a format claim.
 */
public final class FieldLengthBoundsRule implements ValidationRule {

    /** ISO 13616 maximum IBAN length. */
    public static final int MAX_ACCOUNT_LENGTH = 34;

    /** Backstop across all string fields combined. Not a request-size limit — see class javadoc. */
    public static final int MAX_TOTAL_CHARACTERS = 512;

    @Override
    public RuleId id() {
        return RuleId.R10_FIELD_LENGTH_BOUNDS;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        Map<String, String> bounded = new LinkedHashMap<>();
        bounded.put("debtorAccount", request.debtorAccount());
        bounded.put("creditorAccount", request.creditorAccount());

        for (Map.Entry<String, String> field : bounded.entrySet()) {
            String value = field.getValue();
            if (value != null && value.length() > MAX_ACCOUNT_LENGTH) {
                return Optional.of(new RejectionReason(id(), field.getKey(),
                        field.getKey() + " exceeds " + MAX_ACCOUNT_LENGTH + " characters"));
            }
        }

        if (totalCharacters(request) > MAX_TOTAL_CHARACTERS) {
            return Optional.of(new RejectionReason(id(), "*",
                    "payload exceeds " + MAX_TOTAL_CHARACTERS + " characters across all fields"));
        }
        return Optional.empty();
    }

    private static int totalCharacters(PaymentInstructionRequest request) {
        return length(request.endToEndId())
                + length(request.instructedCurrency())
                + length(request.debtorAgent())
                + length(request.creditorAgent())
                + length(request.debtorAccount())
                + length(request.creditorAccount())
                + length(request.debtorCountry())
                + length(request.chargeBearer());
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
