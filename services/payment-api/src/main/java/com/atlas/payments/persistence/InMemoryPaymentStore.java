package com.atlas.payments.persistence;

import com.atlas.payments.domain.PaymentInstruction;
import org.springframework.stereotype.Component;

/**
 * M1 only. Delete this when the M2 ledger lands — do not grow it.
 *
 * <p>It exists so the controller has something real to call and the accepted
 * path can be tested end to end. It is explicitly NOT a model for M2: the brief's
 * named failure mode is a balance column you add to and subtract from, and the
 * way that mistake gets made is by an in-memory stub quietly becoming the design.
 */
@Component
public class InMemoryPaymentStore implements PaymentStore {

    @Override
    public StoredPayment record(PaymentInstruction instruction, String idempotencyKey) {
        throw new UnsupportedOperationException(
                "TODO(M1): a ConcurrentHashMap keyed by idempotency key is enough here. "
                        + "Note that getting this right in memory is not the same problem as "
                        + "getting it right across concurrent transactions - that is M2.");
    }
}
