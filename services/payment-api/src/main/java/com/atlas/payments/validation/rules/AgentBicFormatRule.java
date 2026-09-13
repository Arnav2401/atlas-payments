package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * R05 — debtor and creditor agent BICs must match ISO 9362.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>The structure, not just the length.</b> The brief says "8 or 11
 * alphanumeric", which would accept {@code 1234DE5X}. ISO 9362 is narrower:
 * 4 letters (institution) + 2 letters (ISO 3166-1 country) + 2 alphanumeric
 * (location) + optionally 3 alphanumeric (branch). Never 9 or 10 characters.
 * Enforcing the real structure costs nothing and is the difference between a
 * length check and a format rule.
 *
 * <p><b>Known limit:</b> this validates the <em>shape</em> of a BIC, not its
 * existence. {@code AAAAGB2L} is well-formed and almost certainly not a real
 * institution. Checking existence needs the SWIFT BIC directory, which is
 * licensed data and out of scope; the README should not imply otherwise. The
 * country segment is also not cross-checked against ISO 3166 here — that would
 * be a cheap addition if you want it.
 *
 * <p><b>One rule covering two fields, reporting the first failure.</b> Keeps the
 * rule-to-reason-code mapping 1:1, and {@link RejectionReason#field()} names
 * which agent is at fault. The cost is real: a request with both agents
 * malformed takes two round trips to fix, which cuts against this API's
 * otherwise collect-everything behaviour. The alternative is splitting into two
 * RuleIds. Worth deciding rather than inheriting.
 */
public final class AgentBicFormatRule implements ValidationRule {

    /** ISO 9362: institution(4 alpha) + country(2 alpha) + location(2 alnum) + optional branch(3 alnum). */
    private static final Pattern BIC = Pattern.compile("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    @Override
    public RuleId id() {
        return RuleId.R05_AGENT_BIC_FORMAT;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        return checkOne("debtorAgent", request.debtorAgent())
                .or(() -> checkOne("creditorAgent", request.creditorAgent()));
    }

    private Optional<RejectionReason> checkOne(String field, String bic) {
        if (bic == null || bic.isBlank()) {
            return Optional.of(new RejectionReason(id(), field, field + " is required"));
        }
        if (!BIC.matcher(bic).matches()) {
            return Optional.of(new RejectionReason(id(), field,
                    field + " is not a valid ISO 9362 BIC"));
        }
        return Optional.empty();
    }
}
