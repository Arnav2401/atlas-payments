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
            log.debug("payment {} already has a decision recorded, redelivery ignored",
                    event.paymentId());
        }
    }
}
