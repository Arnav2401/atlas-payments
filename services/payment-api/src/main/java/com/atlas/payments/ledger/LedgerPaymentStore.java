package com.atlas.payments.ledger;

import com.atlas.payments.domain.PaymentInstruction;
import com.atlas.payments.persistence.PaymentStore;
import com.atlas.payments.persistence.StoredPayment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The M2 implementation of {@link PaymentStore}: every accepted payment becomes
 * a balanced journal entry.
 *
 * <h2>How the idempotency race is actually won</h2>
 *
 * <p>The naive shape is check-then-insert: look up the key, and insert if absent.
 * That is a time-of-check-to-time-of-use bug. Two threads both find nothing, both
 * insert, and the ledger gets two entries for one payment. Widening the
 * transaction does not fix it either — under READ COMMITTED neither transaction
 * can see the other's uncommitted row, so both still believe the key is free.
 *
 * <p>So the insert is not guarded, it is <em>attempted</em>. The unique
 * constraint decides the winner, in the database, atomically. The loser catches
 * the violation, starts a fresh transaction — its own is now aborted and unusable
 * — and reads back the winner's journal entry. Both callers get the same
 * {@code paymentId}, and exactly one ledger effect exists.
 *
 * <p>The fast path still checks first, because the common case for a repeat
 * submission is a client retry seconds or minutes later, and a lookup is cheaper
 * than a failed insert. The check is an optimisation; the constraint is the
 * guarantee. That distinction is the whole design.
 */
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
            PaymentSubmissionEntity written = writer.write(instruction, idempotencyKey);
            return new StoredPayment(
                    written.getJournalEntry().getExternalId().toString(),
                    written.getCreatedAt(),
                    false);
        } catch (DataIntegrityViolationException lostTheRace) {
            // Another transaction committed this key first. Ours is aborted;
            // read the winner's result in a new one.
            PaymentSubmissionEntity winner = writer.findByIdempotencyKey(idempotencyKey);
            if (winner == null) {
                // The violation was not the idempotency key - a genuine schema
                // or data fault. Do not swallow it as a duplicate.
                throw lostTheRace;
            }
            return replay(winner, instruction);
        }
    }

    /**
     * Returns the original result for a repeat of the same payment, and refuses
     * a key reused for different content.
     *
     * <p>Returning the first payment's result for a materially different second
     * payment would tell the caller their second payment succeeded when it was
     * never made — the most dangerous possible failure of an idempotent API.
     */
    private StoredPayment replay(PaymentSubmissionEntity submission, PaymentInstruction instruction) {
        String fingerprint = LedgerWriter.fingerprintOf(instruction);

        if (!submission.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key has already been used for a different payment");
        }

        return new StoredPayment(
                submission.getJournalEntry().getExternalId().toString(),
                submission.getCreatedAt(),
                true);
    }
}
