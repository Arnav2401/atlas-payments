package com.atlas.payments.ledger;

public class LedgerConflictException extends RuntimeException {
    public LedgerConflictException(String message) {
        super(message);
    }
}
