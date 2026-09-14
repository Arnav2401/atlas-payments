package com.atlas.payments.persistence;

import com.atlas.payments.domain.PaymentInstruction;

public interface PaymentStore {
    StoredPayment record(PaymentInstruction instruction, String idempotencyKey);
}
