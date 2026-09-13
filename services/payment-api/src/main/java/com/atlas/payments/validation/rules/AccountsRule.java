package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Locale;
import java.util.Optional;

/**
 * R06 — debtor and creditor accounts present and distinct.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>Distinctness is the substance.</b> A payment from an account to itself
 * is a no-op that would still commit two postings to the M2 ledger and still be
 * screened for fraud in M3. Catching it here is cheaper than reasoning about it
 * afterwards.
 *
 * <p><b>Comparison normalises case and surrounding whitespace; the stored value
 * does not.</b> This looks like it contradicts R03, which refuses to normalise
 * currency — the distinction is that R03 normalising would change the value the
 * system <em>accepts</em>, whereas here normalisation only decides whether two
 * values are the <em>same</em>. Account identifiers are case-insensitive in
 * practice (IBANs are defined uppercase), so treating {@code de89...} and
 * {@code DE89...} as different accounts would let the self-payment through on a
 * trivial disguise. Comparing loosely and storing exactly is the conservative
 * combination.
 *
 * <p><b>Not checked here:</b> IBAN check digits (ISO 7064 mod-97). That would be
 * a genuinely stronger rule and is a reasonable thing to add — but it only
 * applies to IBAN-shaped accounts, and this field is not constrained to IBANs,
 * so it would need a format discriminator first.
 */
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

    /** For comparison only. The value the system stores is never rewritten by this rule. */
    private static String normalise(String account) {
        return account.strip().toUpperCase(Locale.ROOT);
    }
}
