package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Locale;
import java.util.Optional;

public final class AccountsRule implements ValidationRule {
    @Override
    public RuleId id() {
        return RuleId.R06_ACCOUNTS_PRESENT_AND_DISTINCT;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        String debtor = request.debtorAccount();
        String creditor = request.creditorAccount();

        if (debtor == null || debtor.isBlank()) {
            return Optional.of(new RejectionReason(id(), "debtorAccount", "debtorAccount is required"));
        }
        if (creditor == null || creditor.isBlank()) {
            return Optional.of(new RejectionReason(id(), "creditorAccount", "creditorAccount is required"));
        }
        if (normalise(debtor).equals(normalise(creditor))) {
            return Optional.of(new RejectionReason(id(), "creditorAccount",
                    "debtorAccount and creditorAccount must be different accounts"));
        }
        return Optional.empty();
    }

    private static String normalise(String account) {
        return account.strip().toUpperCase(Locale.ROOT);
    }
}
