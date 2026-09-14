package com.atlas.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The other half of the outbox pattern: {@link com.atlas.payments.ledger.LedgerWriter}
 * makes an event durable; this class is the separate, asynchronous process
 * that actually reaches Kafka.
 *
 * <h2>Why "durable" and "published" are deliberately two different steps</h2>
 *
 * <p>Publishing to Kafka and marking a row dispatched are not one atomic
 * operation — they cannot be, because they are two different systems and
 * Postgres cannot commit alongside a Kafka broker. That gap is real and
 * this class does not pretend otherwise: if the process dies after Kafka
 * acknowledges the send but before {@code markDispatched} commits, the row
 * is republished on the next poll cycle, and the consumer sees it twice.
 * <b>This is the reason delivery here is at-least-once, not exactly-once</b>
 * — it is not a limitation of this specific implementation, it is a
 * consequence of the outbox pattern's own physics, and it is why
 * {@link com.atlas.payments.fraud.PaymentDecisionConsumer} must be idempotent
 * rather than merely best-effort.
 *
 * <p>What the outbox pattern DOES fully close is the other gap — the one the
 * brief's failure test actually names: crash between the ledger commit and
 * any publish attempt at all. That window has no duplicate risk, because
 * nothing was ever sent; restart, and this poller finds the still-durable row
 * and sends it for the first time. Losing an event and duplicating an event
 * are different failure modes with different fixes, and it is worth being
 * precise about which one this class solves outright (loss) versus which one
 * it pushes downstream (duplication, onto an idempotent consumer).
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    public OutboxPoller(OutboxRepository outbox, KafkaTemplate<String, String> kafkaTemplate, Clock clock) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
    }

    /**
     * Batch-limited, on purpose: under normal operation this finds nothing or
     * a handful of rows, but after an extended Kafka outage the backlog could
     * be large, and a single poll cycle publishing an unbounded backlog would
     * make one cycle's duration unpredictable. Capping it means recovery from
     * a large backlog is several fast cycles instead of one slow, unbounded one.
     */
    @Scheduled(fixedDelayString = "${atlas.outbox.poll-interval-ms:500}")
    public void poll() {
        List<OutboxEntity> batch = outbox.findUndispatchedBatch(Limit.of(BATCH_SIZE));
        for (OutboxEntity row : batch) {
            publishAndMarkDispatched(row);
        }
    }

    /**
     * Each row is independent: one failing send (Kafka unreachable, a broker
     * error) logs and leaves that row for the next cycle, rather than one
     * bad row blocking every row behind it in the batch.
     *
     * <p>A bounded, synchronous wait on the send — not fire-and-forget — is
     * what makes "row marked dispatched" actually mean "Kafka acknowledged
     * it". Fire-and-forget would let this method mark a row dispatched before
     * knowing whether the broker ever received it, silently reopening the
     * exact loss window the outbox pattern exists to close.
     */
    private void publishAndMarkDispatched(OutboxEntity row) {
        try {
            kafkaTemplate.send(row.getTopic(), row.getAggregateId().toString(), row.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception failure) {
            log.warn("outbox publish failed for id={} topic={}, will retry next poll: {}",
                    row.getId(), row.getTopic(), failure.toString());
            return;
        }

        row.markDispatched(Instant.now(clock));
        // outbox.save(...) here is what actually commits the mark, in its own
        // short transaction (Spring Data wraps each repository call in one
        // when none is already active) — no @Transactional method needed on
        // this class, which sidesteps the self-invocation trap entirely
        // (see AccountProvisioner's javadoc for a case where getting that
        // wrong cost real debugging time).
        outbox.save(row);
    }
}
