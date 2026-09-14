package com.atlas.payments.fraud;

import java.util.UUID;

/** No payment exists with the given id. Mapped to 404 — see ApiExceptionHandler. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID paymentId) {
        super("no payment found with id " + paymentId);
    }
}
