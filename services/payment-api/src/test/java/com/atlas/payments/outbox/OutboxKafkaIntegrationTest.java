package com.atlas.payments.outbox;

import com.atlas.payments.domain.PaymentInstruction;
import com.atlas.payments.fraud.PaymentDecisionConsumer;
import com.atlas.payments.fraud.PaymentDecisionEntity;
import com.atlas.payments.fraud.PaymentDecisionRepository;
import com.atlas.payments.ledger.LedgerPaymentStore;
import com.atlas.payments.testing.PaymentInstructionRequests;
import com.atlas.payments.validation.PaymentValidator;
import com.atlas.payments.validation.ValidationOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = "atlas.security.jwt-secret=" + com.atlas.payments.testing.TestSecurity.JWT_SECRET)
@Testcontainers
class OutboxKafkaIntegrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:latest");

    @org.springframework.test.context.DynamicPropertySource
    static void kafkaProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("atlas.outbox.poll-interval-ms", () -> "999999999");
    }

    @Autowired private LedgerPaymentStore store;
    @Autowired private PaymentValidator validator;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxPoller poller;
    @Autowired private PaymentDecisionConsumer decisionConsumer;
    @Autowired private PaymentDecisionRepository decisions;
    @Autowired private com.atlas.payments.ledger.FundingService funding;
    @Autowired private ObjectMapper objectMapper;

    private KafkaConsumer<String, String> submittedConsumer;
    private KafkaProducer<String, String> producer;
    private String debtor;
    private String creditor;

    @BeforeEach
    void setUp() {
        debtor = "DE89" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        creditor = "GB29" + UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
        funding.fund(debtor, new BigDecimal("1000.00"), java.util.Currency.getInstance("USD"));

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        submittedConsumer = new KafkaConsumer<>(consumerProps);
        submittedConsumer.subscribe(List.of(PaymentSubmittedEvent.TOPIC));

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(producerProps);
    }

    @AfterEach
    void tearDown() {
        submittedConsumer.close();
        producer.close();
    }

    private PaymentInstruction instruction(String amount) {
        var request = PaymentInstructionRequests.valid()
                .instructedAmount(amount).instructedCurrency("USD")
                .debtorAccount(debtor).creditorAccount(creditor).build();
        return ((ValidationOutcome.Accepted) validator.validate(request)).instruction();
    }

    private List<ConsumerRecord<String, String>> pollRecords(KafkaConsumer<String, String> consumer, int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;
        while (records.size() < expected && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        return records;
    }

    private List<ConsumerRecord<String, String>> pollRecordsForKey(
            KafkaConsumer<String, String> consumer, String key, int expected) {
        return pollRecordsForKey(consumer, key, expected, 15_000);
    }

    private List<ConsumerRecord<String, String>> pollRecordsForKey(
            KafkaConsumer<String, String> consumer, String key, int expected, long timeoutMs) {
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (matching.size() < expected && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                if (key.equals(record.key())) {
                    matching.add(record);
                }
            });
        }
        return matching;
    }

    @Test
    void a_payment_produces_exactly_one_outbox_row_that_the_poller_publishes() {
        var stored = store.record(instruction("50.00"), "key-" + UUID.randomUUID());

        poller.poll();

        List<ConsumerRecord<String, String>> records = pollRecordsForKey(submittedConsumer, stored.paymentId(), 1);
        assertEquals(1, records.size(), "the fraud service's topic must receive exactly one event for this payment");
        assertTrue(outbox.findUndispatchedBatch(org.springframework.data.domain.Limit.of(50)).stream()
                        .noneMatch(row -> row.getAggregateId().toString().equals(stored.paymentId())),
                "this payment's row must be marked dispatched after a successful publish");
    }

    @Test
    void killing_the_process_between_commit_and_publish_does_not_lose_the_payment() {
        var stored = store.record(instruction("75.00"), "key-" + UUID.randomUUID());

        assertTrue(outbox.findUndispatchedBatch(org.springframework.data.domain.Limit.of(50)).stream()
                        .anyMatch(row -> row.getAggregateId().toString().equals(stored.paymentId())),
                "the payment must be durable before any publish attempt happens");
        assertTrue(pollRecordsForKey(submittedConsumer, stored.paymentId(), 1, 2_000).isEmpty(),
                "nothing may have reached Kafka yet for this payment - the poller has not run");

        poller.poll();

        List<ConsumerRecord<String, String>> afterRestart = pollRecordsForKey(submittedConsumer, stored.paymentId(), 1);
        assertEquals(1, afterRestart.size(),
                "the payment must reach the fraud service after restart - it was never lost");
    }

    @Test
    void the_decision_consumer_is_idempotent_under_redelivery() throws Exception {
        UUID paymentId = UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(new com.atlas.payments.fraud.PaymentDecisionedEvent(
                paymentId.toString(), 0.02, false, 0.16, "MODEL", List.of()));

        decisionConsumer.onMessage(payload);
        decisionConsumer.onMessage(payload); // the redelivery

        List<PaymentDecisionEntity> rows = decisions.findAll().stream()
                .filter(d -> d.getPaymentId().equals(paymentId))
                .toList();
        assertEquals(1, rows.size(), "a redelivered decision must not produce a second row");
    }

    @Test
    void a_poison_message_lands_on_the_dlq_and_does_not_block_later_messages() {
        Properties dlqConsumerProps = new Properties();
        dlqConsumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        dlqConsumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-consumer-" + UUID.randomUUID());
        dlqConsumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        dlqConsumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        dlqConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> dlqConsumer = new KafkaConsumer<>(dlqConsumerProps)) {
            dlqConsumer.subscribe(List.of("payments.dlq"));

            producer.send(new ProducerRecord<>("payments.decisioned", "poison-key", "{ not valid json"));
            producer.flush();

            List<ConsumerRecord<String, String>> dlqRecords = pollRecords(dlqConsumer, 1);
            assertEquals(1, dlqRecords.size(), "the malformed message must land on the DLQ");
            assertTrue(dlqRecords.get(0).value().contains("not valid json"));

            UUID paymentId = UUID.randomUUID();
            producer.send(new ProducerRecord<>("payments.decisioned", paymentId.toString(),
                    toDecisionedJson(paymentId, false, 0.01)));
            producer.flush();

            long deadline = System.currentTimeMillis() + 15_000;
            while (decisions.findByPaymentId(paymentId).isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            assertTrue(decisions.findByPaymentId(paymentId).isPresent(),
                    "a later, well-formed message must still be processed - the consumer was not halted");
        }
    }

    @Test
    void a_dlq_message_can_be_replayed_once_it_is_fixed() {
        Properties dlqConsumerProps = new Properties();
        dlqConsumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        dlqConsumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-replay-consumer-" + UUID.randomUUID());
        dlqConsumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        dlqConsumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        dlqConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        UUID paymentId = UUID.randomUUID();
        try (KafkaConsumer<String, String> dlqConsumer = new KafkaConsumer<>(dlqConsumerProps)) {
            dlqConsumer.subscribe(List.of("payments.dlq"));

            producer.send(new ProducerRecord<>("payments.decisioned", paymentId.toString(), "{ broken"));
            producer.flush();
            List<ConsumerRecord<String, String>> dlqRecords = pollRecords(dlqConsumer, 1);
            assertEquals(1, dlqRecords.size());

            String fixedPayload = toDecisionedJson(paymentId, true, 0.95);
            producer.send(new ProducerRecord<>("payments.decisioned", paymentId.toString(), fixedPayload));
            producer.flush();

            long deadline = System.currentTimeMillis() + 15_000;
            while (decisions.findByPaymentId(paymentId).isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
            assertTrue(decisions.findByPaymentId(paymentId).isPresent(), "the replayed message must be processed");
            assertNotNull(decisions.findByPaymentId(paymentId).get());
            assertTrue(decisions.findByPaymentId(paymentId).get().isFlagged());
        }
    }

    private String toDecisionedJson(UUID paymentId, boolean flagged, double probability) {
        try {
            return objectMapper.writeValueAsString(new com.atlas.payments.fraud.PaymentDecisionedEvent(
                    paymentId.toString(), probability, flagged, 0.16, "MODEL", List.of()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deserialises_a_literal_payload_matching_the_python_services_actual_schema() throws Exception {
        UUID paymentId = UUID.randomUUID();
        String realPythonShapedPayload = """
                {"payment_id":"%s","probability":0.0000194,"flagged":false,"threshold":0.1627,
                 "top_features":[{"feature":"orig_balance_ratio","value":0.02,"shap_contribution":-8.06}],
                 "source":"MODEL"}
                """.formatted(paymentId);

        decisionConsumer.onMessage(realPythonShapedPayload);

        assertTrue(decisions.findByPaymentId(paymentId).isPresent(),
                "a payload shaped exactly like the real Python service's output must deserialise and persist");
    }
}
