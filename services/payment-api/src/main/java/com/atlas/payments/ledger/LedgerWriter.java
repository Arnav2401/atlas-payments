package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;
import com.atlas.payments.outbox.OutboxEntity;
import com.atlas.payments.outbox.OutboxRepository;
import com.atlas.payments.outbox.PaymentSubmittedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class LedgerWriter {
    private final AccountRepository accounts;
    private final AccountProvisioner accountProvisioner;
    private final JournalEntryRepository journalEntries;
    private final PaymentSubmissionRepository submissions;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LedgerWriter(AccountRepository accounts,
                        AccountProvisioner accountProvisioner,
                        JournalEntryRepository journalEntries,
                        PaymentSubmissionRepository submissions,
                        OutboxRepository outbox,
                        ObjectMapper objectMapper,
                        Clock clock) {
        this.accounts = accounts;
        this.accountProvisioner = accountProvisioner;
        this.journalEntries = journalEntries;
        this.submissions = submissions;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LedgerWriteResult write(PaymentInstruction instruction, String idempotencyKey) {
        String currencyCode = instruction.instructedCurrency().getCurrencyCode();

        long amountMinor = Money.toMinorUnits(
                instruction.instructedAmount(), instruction.instructedCurrency());

        // Only the debtor is locked: its balance is read and decided on below, so
        // it needs the version bump. The creditor is only ever written to.
        AccountEntity debtor = resolveAccount(instruction.debtorAccount(), currencyCode, true);
        AccountEntity creditor = resolveAccount(instruction.creditorAccount(), currencyCode, false);

        // Read once and handed back, so the funds check and fraud scoring both see
        // the balance as it was before this payment landed.
        long debtorBalanceBeforeMinor = accounts.availableMinorUnits(debtor.getAccountNumber());
        long creditorBalanceBeforeMinor = accounts.availableMinorUnits(creditor.getAccountNumber());

        assertSufficientFunds(debtor, debtorBalanceBeforeMinor, amountMinor);

        Instant now = Instant.now(clock);

        JournalEntryEntity entry = new JournalEntryEntity(
                UUID.randomUUID(), instruction.endToEndId(), "customer credit transfer", now);

        // Positive is a debit, negative a credit. The two legs sum to zero, which
        // the deferred constraint trigger checks at COMMIT.
        entry.addPosting(debtor, amountMinor, currencyCode);
        entry.addPosting(creditor, -amountMinor, currencyCode);

        journalEntries.save(entry);

        PaymentSubmissionEntity submission = submissions.save(new PaymentSubmissionEntity(
                idempotencyKey, RequestFingerprint.of(instruction), entry, now));

        writeOutboxEvent(entry, instruction, currencyCode, debtorBalanceBeforeMinor, creditorBalanceBeforeMinor, now);

        return new LedgerWriteResult(submission, debtorBalanceBeforeMinor, creditorBalanceBeforeMinor);
    }

    private void writeOutboxEvent(JournalEntryEntity entry, PaymentInstruction instruction, String currencyCode,
                                  long debtorBalanceBeforeMinor, long creditorBalanceBeforeMinor, Instant now) {
        var currency = instruction.instructedCurrency();
        var event = new PaymentSubmittedEvent(
                entry.getExternalId().toString(),
                instruction.endToEndId(),
                instruction.instructedAmount(),
                false, // always false: this API only models credit transfers, never cash-out
                instruction.debtorAccount(),
                instruction.creditorAccount(),
                Money.fromMinorUnits(debtorBalanceBeforeMinor, currency),
                Money.fromMinorUnits(creditorBalanceBeforeMinor, currency),
                now.atZone(java.time.ZoneOffset.UTC).getHour());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("PaymentSubmittedEvent must always be serialisable", impossible);
        }

        outbox.save(new OutboxEntity(entry.getExternalId(), PaymentSubmittedEvent.TOPIC, payload, now));
    }

    private void assertSufficientFunds(AccountEntity debtor, long availableMinorUnits, long amountMinor) {
        if (debtor.getAccountType() == AccountType.SETTLEMENT) {
            return;
        }

        if (availableMinorUnits < amountMinor) {
            throw new InsufficientFundsException(availableMinorUnits, amountMinor);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentSubmissionEntity findByIdempotencyKey(String idempotencyKey) {
        return submissions.findByIdempotencyKey(idempotencyKey).orElse(null);
    }

    static String fingerprintOf(PaymentInstruction instruction) {
        return RequestFingerprint.of(instruction);
    }

    private AccountEntity resolveAccount(String accountNumber, String currencyCode, boolean requiresLock) {
        try {
            accountProvisioner.createIfAbsent(accountNumber, currencyCode, AccountType.CUSTOMER, Instant.now(clock));
        } catch (DataIntegrityViolationException lostTheRace) {
            // A concurrent payment created the account first. The postcondition is
            // that the row exists, and it does.
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
