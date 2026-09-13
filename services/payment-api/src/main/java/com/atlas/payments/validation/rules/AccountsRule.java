package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R06_ACCOUNTS_PRESENT_AND_DISTINCT — debtor and creditor accounts present and distinct.
 *
 * <p>Distinctness is the real content: a payment from an account to itself is a no-op that would still commit two postings in M2. Decide whether comparison is case-sensitive and whether you normalise whitespace first.
 */
public final class AccountsRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R06_ACCOUNTS_PRESENT_AND_DISTINCT;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R06_ACCOUNTS_PRESENT_AND_DISTINCT");
    }
}
