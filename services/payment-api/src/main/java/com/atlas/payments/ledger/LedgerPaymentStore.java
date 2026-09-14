package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;
import com.atlas.payments.persistence.PaymentStore;
import com.atlas.payments.persistence.StoredPayment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class LedgerPaymentStore implements PaymentStore {
    private final LedgerWriter writer;

    public LedgerPaymentStore(LedgerWriter writer) {
        this.writer = writer;
    }

    @Override
    public StoredPayment record(PaymentInstruction instruction, String idempotencyKey) {
        PaymentSubmissionEntity existing = writer.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return replay(existing, instruction);
        }

        try {
            LedgerWriteResult result = writer.write(instruction, idempotencyKey);
            PaymentSubmissionEntity written = result.submission();
            return new StoredPayment(
                    written.getJournalEntry().getExternalId().toString(),
                    written.getCreatedAt(),
                    false,
                    result.debtorBalanceBeforeMinor(),
                    result.creditorBalanceBeforeMinor());
        } catch (DataIntegrityViolationException lostTheRace) {
            // The losing transaction is aborted, so this re-read has to happen in a
            // new one - which is why the lookup lives on LedgerWriter (a different
            // bean, REQUIRES_NEW) rather than inline here.
            PaymentSubmissionEntity winner = writer.findByIdempotencyKey(idempotencyKey);
            if (winner == null) {
                throw lostTheRace;
            }
            return replay(winner, instruction);
        }
    }

    private StoredPayment replay(PaymentSubmissionEntity submission, PaymentInstruction instruction) {
        String fingerprint = LedgerWriter.fingerprintOf(instruction);

        if (!submission.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key has already been used for a different payment");
        }

        return new StoredPayment(
                submission.getJournalEntry().getExternalId().toString(),
                submission.getCreatedAt(),
                true,
                null,
                null);
    }
}
