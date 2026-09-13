package com.atlas.payments.persistence;

import com.atlas.payments.domain.PaymentInstruction;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M1 only. Delete this when the M2 ledger lands — do not grow it.
 *
 * <p>It exists so the controller has something real to call. It is explicitly
 * NOT a model for M2: the brief's named failure mode is a balance column you add
 * to and subtract from, and the way that mistake gets made is by an in-memory
 * stub quietly becoming the design.
 *
 * <h2>What this does and does not prove</h2>
 *
 * <p>{@code putIfAbsent} is atomic, so two concurrent submissions of the same
 * idempotency key produce one stored payment and one {@code alreadyExisted}.
 * That is genuinely correct — <em>for a single process holding one map</em>.
 *
 * <p>It proves nothing about M2. There the same question spans a database
 * transaction and multiple application instances, where atomicity has to come
 * from a unique constraint and a chosen isolation level rather than from a
 * data structure. Passing a concurrency test against this class would be
 * actively misleading, which is why there is no such test here.
 *
 * <h2>Known gap, deliberate</h2>
 *
 * <p>The same key submitted with a <em>different</em> payment returns the
 * original result rather than conflicting. A real implementation fingerprints
 * the request and rejects a key reused for different content. That belongs in
 * M2 with the rest of the idempotency machinery.
 */
@Component
public class InMemoryPaymentStore implements PaymentStore {

    private final Map<String, StoredPayment> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryPaymentStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public StoredPayment record(PaymentInstruction instruction, String idempotencyKey) {
        StoredPayment candidate = new StoredPayment(
                UUID.randomUUID().toString(), Instant.now(clock), false);

        StoredPayment existing = byIdempotencyKey.putIfAbsent(idempotencyKey, candidate);

        return existing == null
                ? candidate
                : new StoredPayment(existing.paymentId(), existing.recordedAt(), true);
    }
}
