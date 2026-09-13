package com.atlas.payments.persistence;

import java.time.Instant;

/**
 * The outcome of recording a payment.
 *
 * <p>{@code alreadyExisted} exists for M2: under an idempotency key, a repeat
 * submission must return the original result rather than a second ledger effect,
 * and the caller needs to know which happened. Carrying it from M1 means the
 * controller's response logic does not change when the real ledger lands.
 */
public record StoredPayment(String paymentId, Instant recordedAt, boolean alreadyExisted) {}
