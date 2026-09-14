package com.atlas.payments.persistence;

import java.time.Instant;

/**
 * The outcome of recording a payment.
 *
 * <p>{@code alreadyExisted} exists for M2: under an idempotency key, a repeat
 * submission must return the original result rather than a second ledger effect,
 * and the caller needs to know which happened. Carrying it from M1 means the
 * controller's response logic does not change when the real ledger lands.
 *
 * <p>{@code debtorBalanceBeforeMinor} / {@code creditorBalanceBeforeMinor} exist
 * for M3: fraud scoring needs the balance a payment was decided against.
 * <b>Null on a replay</b> — deliberately, not "the current balance". A replayed
 * idempotent submission is the same payment already decided once; scoring it
 * again would be a second, pointless fraud check against balance state that has
 * moved on since the original decision, not a "fresh" answer. The controller
 * skips fraud scoring entirely when these are null; see PaymentController.
 */
public record StoredPayment(
        String paymentId,
        Instant recordedAt,
        boolean alreadyExisted,
        Long debtorBalanceBeforeMinor,
        Long creditorBalanceBeforeMinor
) {}
