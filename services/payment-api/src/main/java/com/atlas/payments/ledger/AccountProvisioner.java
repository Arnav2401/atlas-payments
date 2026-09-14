package com.atlas.payments.ledger;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Get-or-create for accounts, made safe under concurrency.
 *
 * <h2>The bug this exists to fix</h2>
 *
 * <p>A payment references a debtor and a creditor account, and either can be
 * new. "Look it up, insert if absent" is check-then-insert — the same
 * time-of-check-to-time-of-use shape as the idempotency race: two threads
 * paying the same not-yet-existing creditor both find nothing and both insert,
 * and the second insert fails {@code accounts_account_number_key}. Unlike the
 * idempotency case this has nothing to do with retrying a payment — it is any
 * two <em>different</em> first-time payments to the same new counterparty,
 * arriving together.
 *
 * <p>The fix is the same shape as {@link LedgerPaymentStore}: attempt the
 * insert, let the unique constraint decide, and treat a conflict as "someone
 * else already created it" rather than as a fault.
 *
 * <h2>Only this one method is public, and the reason is a bug this class had</h2>
 *
 * <p>An earlier version of this class offered a convenience method that caught
 * the conflict itself, by calling {@link #createIfAbsent} from another method on
 * the <em>same</em> object. That is self-invocation, and Spring's proxy-based
 * {@code @Transactional} does not intercept it — the annotation was silently
 * ignored, {@code createIfAbsent} ran inside whichever transaction the caller
 * already had open, and a failed insert corrupted that transaction instead of
 * one scoped to just this attempt. It surfaced as Hibernate refusing to flush a
 * transient entity with a null identifier — a confusing symptom for what was, at
 * root, this exact self-invocation trap, on the exact bug {@link LedgerWriter}'s
 * own javadoc already warns about.
 *
 * <p>The fix is to make the crossing of a real proxy boundary unavoidable: this
 * class exposes only the transactional method, and every caller — which is
 * necessarily a different bean — must catch the conflict itself. There is
 * nowhere left to make the mistake again.
 */
@Component
public class AccountProvisioner {

    private final AccountRepository accounts;

    public AccountProvisioner(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Attempts to create the account. Throws {@code DataIntegrityViolationException}
     * if it already exists — callers must catch that and treat it as success, not
     * call this expecting it to handle the conflict itself.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfAbsent(String accountNumber, String currencyCode, AccountType type, Instant now) {
        if (accounts.findByAccountNumber(accountNumber).isPresent()) {
            return;
        }
        accounts.save(new AccountEntity(accountNumber, currencyCode, type, now));
    }
}
