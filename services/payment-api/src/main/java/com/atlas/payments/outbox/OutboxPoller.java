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

    @Scheduled(fixedDelayString = "${atlas.outbox.poll-interval-ms:500}")
    public void poll() {
        List<OutboxEntity> batch = outbox.findUndispatchedBatch(Limit.of(BATCH_SIZE));
        for (OutboxEntity row : batch) {
            publishAndMarkDispatched(row);
        }
    }

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
        outbox.save(row);
    }
}
