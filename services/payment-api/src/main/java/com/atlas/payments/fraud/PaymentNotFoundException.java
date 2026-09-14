package com.atlas.payments.fraud;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID paymentId) {
        super("no payment found with id " + paymentId);
    }
}
