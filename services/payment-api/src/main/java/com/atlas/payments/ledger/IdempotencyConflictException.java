package com.atlas.payments.ledger;

/**
 * An idempotency key was reused for a materially different payment.
 *
 * <p>Distinct from the ordinary repeat case. A repeat of the <em>same</em>
 * payment returns the original result — that is the point of idempotency.
 * Reusing a key for different content is a caller bug, and silently returning
 * the first payment's result would tell them their second payment succeeded when
 * it was never made.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
