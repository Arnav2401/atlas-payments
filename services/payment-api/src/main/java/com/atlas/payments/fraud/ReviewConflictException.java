package com.atlas.payments.fraud;

public class ReviewConflictException extends RuntimeException {
    public ReviewConflictException(String message) {
        super(message);
    }
}
