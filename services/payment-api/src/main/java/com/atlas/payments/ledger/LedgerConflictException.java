package com.atlas.payments.ledger;

/**
 * The payment cannot be posted to the ledger as instructed — for example an
 * account that exists in a different currency.
 *
 * <p>Deliberately not an {@code IllegalStateException} or a bare runtime
 * exception: the brief's requirement is that a conflict is handled explicitly
 * and surfaces as a considered response rather than a 500.
 */
public class LedgerConflictException extends RuntimeException {

    public LedgerConflictException(String message) {
        super(message);
    }
}
