package com.atlas.payments.persistence;

import java.time.Instant;

public record StoredPayment(
        String paymentId,
        Instant recordedAt,
        boolean alreadyExisted,
        Long debtorBalanceBeforeMinor,
        Long creditorBalanceBeforeMinor
) {}
