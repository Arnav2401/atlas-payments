package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The transactional core: writes one journal entry, its postings, and the
 * idempotency record in a single database transaction.
 *
 * <p>Separated from {@link LedgerPaymentStore} for a concrete reason rather than
 * tidiness. When the unique constraint on the idempotency key fires, the
 * PostgreSQL transaction is aborted and no further statement can run inside it —
 * so the losing thread cannot re-read the winner's result in the same
 * transaction. The retry has to happen in a new one. Spring's proxies do not
 * intercept self-invocation, so calling a {@code @Transactional} method from the
 * same class would silently run without starting one. Two beans, two
 * transactions, no surprises.
 */
@Component
public class LedgerWriter {

    private final AccountRepository accounts;
    private final AccountProvisioner accountProvisioner;
    private final JournalEntryRepository journalEntries;
    private final PaymentSubmissionRepository submissions;
    private final Clock clock;

    public LedgerWriter(AccountRepository accounts,
                        AccountProvisioner accountProvisioner,
                        JournalEntryRepository journalEntries,
                        PaymentSubmissionRepository submissions,
                        Clock clock) {
        this.accounts = accounts;
        this.accountProvisioner = accountProvisioner;
        this.journalEntries = journalEntries;
        this.submissions = submissions;
        this.clock = clock;
    }

    /**
     * <h2>Isolation level: READ COMMITTED (the PostgreSQL default), deliberately</h2>
     *
     * <p>The usual reason to reach for REPEATABLE READ or SERIALIZABLE is a
     * read-modify-write cycle: read a balance, decide on it, write it back. This
     * ledger has no such cycle. Postings are append-only and a balance is derived
     * by summing them, so there is no value read here whose staleness could
     * produce a wrong write. The anomaly that higher isolation buys protection
     * from is not reachable, because the pattern that creates it is absent.
     *
     * <p>The one invariant that genuinely needs protecting — exactly one ledger
     * effect per idempotency key — is protected by a unique constraint, which the
     * database enforces regardless of isolation level and regardless of how many
     * application instances are running. Raising the isolation level would add
     * serialisation failures and retry logic while protecting nothing extra.
     *
     * <h2>Except for the funds check, which is a read-modify-write</h2>
     *
     * <p>The available-funds check below reads a balance and decides on it, which
     * is exactly the pattern READ COMMITTED does not protect: two concurrent
     * payments could each read a sufficient balance and both commit, overdrawing
     * the account. That is a lost update and it is reachable here.
     *
     * <p>Rather than raise the isolation level for the whole transaction, the
     * account is read with {@code OPTIMISTIC_FORCE_INCREMENT}, which narrows the
     * protection to the one row whose staleness matters. The second payment to
     * commit fails on the version check and is reported as a conflict.
     *
     * <p><b>Why optimistic and not {@code SELECT FOR UPDATE}.</b> Pessimistic
     * locking serialises every payment on an account whether or not there is
     * contention, and holds the lock for the whole transaction. Optimistic pays
     * nothing when conflicts are rare — which is the truth for a retail account —
     * and pays a retry when they are not. The calculus inverts for a heavily used
     * corporate or treasury account, where conflicts are the norm and a retry
     * storm is worse than waiting; that is the case for {@code SELECT FOR UPDATE}
     * on those accounts specifically.
     *
     * <p>The conflict is <b>not retried here</b>. It surfaces as a 409, which is
     * safe advice precisely because the operation is idempotent under the key:
     * the caller can resubmit with the same Idempotency-Key and either win the
     * race or be told their payment already exists. A server-side retry with
     * backoff is the obvious refinement and belongs with M5's resilience work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentSubmissionEntity write(PaymentInstruction instruction, String idempotencyKey) {
        String currencyCode = instruction.instructedCurrency().getCurrencyCode();

        long amountMinor = Money.toMinorUnits(
                instruction.instructedAmount(), instruction.instructedCurrency());

        // Only the debtor is read-and-decided-upon (the funds check below), so
        // only the debtor needs the forced version increment. See the javadoc
        // on resolveAccount for why locking the creditor too would be pure cost.
        AccountEntity debtor = resolveAccount(instruction.debtorAccount(), currencyCode, true);
        AccountEntity creditor = resolveAccount(instruction.creditorAccount(), currencyCode, false);

        assertSufficientFunds(debtor, amountMinor);

        Instant now = Instant.now(clock);

        JournalEntryEntity entry = new JournalEntryEntity(
                UUID.randomUUID(), instruction.endToEndId(), "customer credit transfer", now);

        // Positive is a debit, negative a credit. These two legs sum to zero,
        // which the deferred constraint trigger verifies at COMMIT.
        entry.addPosting(debtor, amountMinor, currencyCode);
        entry.addPosting(creditor, -amountMinor, currencyCode);

        journalEntries.save(entry);

        return submissions.save(new PaymentSubmissionEntity(
                idempotencyKey, RequestFingerprint.of(instruction), entry, now));
    }

    /**
     * The funds check, and the reason the account was read under a forced version
     * increment.
     *
     * <p>SETTLEMENT accounts are exempt: they are the bank's own position and are
     * expected to run negative. Applying a customer overdraft rule to a nostro
     * account would block every funding entry.
     *
     * <p>Note the sign. A customer deposit is a liability of the bank, so a
     * funded customer account carries a credit — negative — balance, and what
     * they can spend is the negation of it.
     */
    private void assertSufficientFunds(AccountEntity debtor, long amountMinor) {
        if (debtor.getAccountType() == AccountType.SETTLEMENT) {
            return;
        }

        long available = accounts.availableMinorUnits(debtor.getAccountNumber());
        if (available < amountMinor) {
            throw new InsufficientFundsException(available, amountMinor);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentSubmissionEntity findByIdempotencyKey(String idempotencyKey) {
        return submissions.findByIdempotencyKey(idempotencyKey).orElse(null);
    }

    static String fingerprintOf(PaymentInstruction instruction) {
        return RequestFingerprint.of(instruction);
    }

    /**
     * Get-or-create, in two steps that must not be collapsed back into one.
     *
     * <p><b>Provisioning is a separate step because of a second concurrency
     * hazard this class used to have.</b> Two different first-time payments to
     * the same new counterparty — nothing to do with idempotency — used to race
     * on {@code findByAccountNumber} finding nothing for both, and the second
     * insert failed the unique constraint from inside this transaction, which
     * aborted the whole payment. {@link AccountProvisioner#createIfAbsent} fixes
     * that the same way the idempotency key is handled: attempt the insert in
     * its own transaction, and treat a conflict caught here as "it exists now"
     * rather than as a fault. By the time control reaches {@code findForUpdate}
     * below, the row is guaranteed to exist — created by this call or by
     * whichever concurrent call won.
     *
     * <p>The second step reads the now-guaranteed-to-exist row, and is where a
     * second decision lives: whether that read forces a version increment.
     *
     * <h2>Why the creditor is deliberately read without the lock</h2>
     *
     * <p>The forced increment exists to protect the funds check — it is what
     * makes "read a balance, decide on it" safe under concurrency. The creditor
     * is never read-and-decided-upon here; it is only written to, unconditionally.
     * Locking it anyway would add contention that protects nothing: a payroll run
     * crediting a thousand small payments to one popular merchant account would
     * serialise entirely on a version check that guards an invariant the code
     * never actually needs about the creditor side. This was found by a test —
     * eight concurrent first-time payments to one new shared creditor failed with
     * optimistic-lock exceptions before this distinction was made explicit.
     *
     * <p>If a future feature reads a creditor's balance and decides something
     * from it — a holding-limit or AML threshold check, say — that account
     * becomes read-and-decided-upon too, and {@code requiresLock} must flip to
     * {@code true} for it. Until then, forcing it costs correctness for nothing.
     *
     * <p><b>Known compromise, and a real one, separate from the concurrency
     * question.</b> A bank does not open an account because a stranger sent
     * money to it — an unknown account is a rejection, and onboarding is a
     * separate, regulated process. Auto-creating here keeps the demo and the
     * 10,000-payment reconciliation run self-contained. The production shape is
     * a validation rule that rejects an unknown creditor account plus an
     * onboarding path, and the README must not describe this as if that already
     * exists.
     */
    private AccountEntity resolveAccount(String accountNumber, String currencyCode, boolean requiresLock) {
        try {
            accountProvisioner.createIfAbsent(accountNumber, currencyCode, AccountType.CUSTOMER, Instant.now(clock));
        } catch (DataIntegrityViolationException lostTheRace) {
            // Another transaction created this account first, between our check
            // and our insert. Fine - the postcondition is the row exists, and it
            // does. AccountProvisioner is a genuinely different bean, so this
            // catch is outside the REQUIRES_NEW transaction that failed; this
            // method's own transaction (write()'s) is untouched by it.
        }

        var lookup = requiresLock ? accounts.findForUpdate(accountNumber) : accounts.findByAccountNumber(accountNumber);
        AccountEntity existing = lookup.orElseThrow(() -> new IllegalStateException(
                "account " + accountNumber + " must exist after provisioning"));

        if (!existing.getCurrency().equals(currencyCode)) {
            throw new LedgerConflictException(
                    "account " + accountNumber + " is held in " + existing.getCurrency()
                            + " and cannot receive a " + currencyCode + " posting");
        }
        return existing;
    }
}
