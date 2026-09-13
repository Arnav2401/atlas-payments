package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;
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
    private final JournalEntryRepository journalEntries;
    private final PaymentSubmissionRepository submissions;
    private final Clock clock;

    public LedgerWriter(AccountRepository accounts,
                        JournalEntryRepository journalEntries,
                        PaymentSubmissionRepository submissions,
                        Clock clock) {
        this.accounts = accounts;
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
     * <p><b>What would change this answer.</b> Add an available-funds check and
     * the read-modify-write appears immediately: two concurrent payments could
     * each read a sufficient balance and both commit, overdrawing the account.
     * That is a lost update, and under READ COMMITTED it is reachable. The fix is
     * then SERIALIZABLE with retry on serialisation failure, or {@code SELECT
     * FOR UPDATE} on the account row — and at that point the {@code version}
     * column on accounts starts doing real work. The funds check is not
     * implemented, so this rationale is true today and has a documented expiry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentSubmissionEntity write(PaymentInstruction instruction, String idempotencyKey) {
        String currencyCode = instruction.instructedCurrency().getCurrencyCode();

        AccountEntity debtor = resolveAccount(instruction.debtorAccount(), currencyCode);
        AccountEntity creditor = resolveAccount(instruction.creditorAccount(), currencyCode);

        long amountMinor = Money.toMinorUnits(
                instruction.instructedAmount(), instruction.instructedCurrency());

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

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentSubmissionEntity findByIdempotencyKey(String idempotencyKey) {
        return submissions.findByIdempotencyKey(idempotencyKey).orElse(null);
    }

    static String fingerprintOf(PaymentInstruction instruction) {
        return RequestFingerprint.of(instruction);
    }

    /**
     * Get-or-create.
     *
     * <p><b>Known compromise, and a real one.</b> A bank does not open an account
     * because a stranger sent money to it — an unknown account is a rejection,
     * and onboarding is a separate, regulated process. Auto-creating here keeps
     * the demo and the 10,000-payment reconciliation run self-contained. The
     * production shape is a validation rule that rejects an unknown creditor
     * account plus an onboarding path, and the README must not describe this as
     * if that already exists.
     */
    private AccountEntity resolveAccount(String accountNumber, String currencyCode) {
        AccountEntity existing = accounts.findByAccountNumber(accountNumber).orElse(null);

        if (existing == null) {
            return accounts.save(new AccountEntity(accountNumber, currencyCode, Instant.now(clock)));
        }
        if (!existing.getCurrency().equals(currencyCode)) {
            throw new LedgerConflictException(
                    "account " + accountNumber + " is held in " + existing.getCurrency()
                            + " and cannot receive a " + currencyCode + " posting");
        }
        return existing;
    }
}
