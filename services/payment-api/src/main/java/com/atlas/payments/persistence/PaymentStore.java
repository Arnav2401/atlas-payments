package com.atlas.payments.persistence;

import com.atlas.payments.domain.PaymentInstruction;

/**
 * The M2 seam.
 *
 * <p>M1 has an in-memory implementation; M2 replaces it with the double-entry
 * ledger write. This signature should not have to change when that happens —
 * if it does, the seam was drawn in the wrong place, and that is worth noticing
 * now rather than in week three.
 *
 * <p>The idempotency key is a parameter rather than a field on
 * {@link PaymentInstruction} because it is a property of the <em>submission</em>,
 * not of the payment. Two submissions of the same payment with different keys are
 * two payments; the same key twice is one.
 */
public interface PaymentStore {

    StoredPayment record(PaymentInstruction instruction, String idempotencyKey);
}
