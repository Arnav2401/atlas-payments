package com.atlas.payments.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumes {@code payments.decisioned} and writes the verdict to
 * {@link PaymentDecisionEntity}, idempotently.
 *
 * <h2>How this avoids double-processing on a rebalance</h2>
 *
 * <p>It does not try to. A consumer group rebalance — another instance joins
 * or leaves, or this one is slow enough to be considered dead and its
 * partitions reassigned — can hand the same message to a consumer twice: once
 * before a rebalance interrupts an in-flight, uncommitted offset, and again
 * to whichever consumer inherits that partition. Kafka's delivery guarantee
 * here is at-least-once (see {@link com.atlas.payments.outbox.OutboxPoller}'s
 * javadoc for the matching gap on the publish side), and no amount of
 * consumer-side cleverness turns at-least-once into exactly-once on its own.
 *
 * <p>So the fix is not "avoid seeing it twice" — it is "seeing it twice must be
 * harmless". The insert is attempted, not guarded by a check first; {@code
 * payment_id}'s unique constraint decides whether this delivery is the first
 * or a repeat. A repeat throws {@link DataIntegrityViolationException}, which
 * is caught and treated as success, not as an error to retry or dead-letter —
 * this is the exact same shape as {@link com.atlas.payments.ledger.LedgerPaymentStore}'s
 * idempotency-key handling and {@link com.atlas.payments.ledger.AccountProvisioner}'s
 * get-or-create, applied a third time to a third kind of race.
 *
 * <h2>Poison messages</h2>
 *
 * <p>A malformed payload throws out of {@code onMessage} and is deliberately
 * not caught here. Spring Kafka's configured {@code DefaultErrorHandler} (see
 * {@code KafkaConfig}) retries it a bounded number of times, then publishes it
 * to {@code payments.dlq} and commits the offset — so one poison message
 * delays processing briefly but does not halt every message behind it in the
 * partition forever. See the README for the DLQ replay procedure.
 */
@Component
public class PaymentDecisionConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentDecisionConsumer.class);

    private final PaymentDecisionRepository decisions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentDecisionConsumer(PaymentDecisionRepository decisions, ObjectMapper objectMapper, Clock clock) {
        this.decisions = decisions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @KafkaListener(topics = "payments.decisioned", groupId = "payment-api-decision-consumer")
    public void onMessage(String payload) throws Exception {
        // A parse failure throws unchecked from readValue and propagates —
        // deliberately not wrapped in a try/catch that would swallow it. See
        // this class's javadoc: that is what routes it to the DLQ.
        PaymentDecisionedEvent event = objectMapper.readValue(payload, PaymentDecisionedEvent.class);

        PaymentDecisionEntity decision = new PaymentDecisionEntity(
                UUID.fromString(event.paymentId()),
                event.source(),
                event.flagged(),
                event.probability(),
                Instant.now(clock),
                payload);

        try {
            decisions.save(decision);
        } catch (DataIntegrityViolationException alreadyDecided) {
            // A redelivery of a payment already decided - the unique
            // constraint on payment_id caught it. This is success, not an
            // error: the postcondition ("a decision row exists for this
            // payment") already held before this call.
            log.debug("payment {} already has a decision recorded, redelivery ignored",
                    event.paymentId());
        }
    }
}
