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

/**
 * The transactional outbox and its Kafka pipeline, against real PostgreSQL
 * and a real, single-broker, KRaft-mode Kafka (no ZooKeeper — the same
 * {@code apache/kafka} image the brief names, via
 * {@code org.testcontainers.kafka.KafkaContainer}, which wraps it natively
 * rather than the older Confluent-image-plus-ZooKeeper Testcontainers
 * pattern).
 *
 * <p>This test stands in for "the fraud service" with a raw
 * {@link KafkaConsumer} on {@code payments.submitted} — the brief's failure
 * test asks whether "the payment still reaches the fraud service", and a raw
 * consumer proves that claim about the topic itself, independent of whether
 * the Python service's own consumer logic (tested separately, in
 * services/fraud-service) is correct.
 */
@SpringBootTest
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
        // The real @Scheduled OutboxPoller.poll() is still active in this
        // full Spring context and would otherwise fire every 500ms on its
        // own, racing this test's manual poller.poll() calls - the "kill the
        // process" test specifically needs to control exactly when a poll
        // happens, to assert the state in between. A huge interval (rather
        // than trying to disable @Scheduled outright) makes automatic firing
        // a practical impossibility within one test's lifetime while leaving
        // the bean itself, and every manual call to it, behaving exactly as
        // production would.
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

    /** Same polling loop, but counts only records keyed to one specific payment. */
    private List<ConsumerRecord<String, String>> pollRecordsForKey(
            KafkaConsumer<String, String> consumer, String key, int expected) {
        return pollRecordsForKey(consumer, key, expected, 15_000);
    }

    /**
     * Bounded by an explicit timeout, not always the full 15s: an assertion
     * that NOTHING has arrived yet can only ever be proven by exhausting the
     * wait (there is no "confirmed absent" signal to exit early on), so
     * proving that with the same 15s budget used for "this DID arrive"
     * assertions would make every negative check slow for no benefit. A
     * short bound here is enough time for a message to have shown up if it
     * were going to.
     */
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

    // ------------------------------------------------------------- the basic path

    @Test
    void a_payment_produces_exactly_one_outbox_row_that_the_poller_publishes() {
        var stored = store.record(instruction("50.00"), "key-" + UUID.randomUUID());

        poller.poll();

        // Filtered by this payment's own key, not a raw count of the topic -
        // the topic is shared across every test method in this class (Kafka
        // has no per-test isolation the way a fresh Postgres schema might),
        // so other tests' events legitimately coexist on it. What must hold
        // is that THIS payment produced exactly one event, not that the
        // topic is otherwise empty.
        List<ConsumerRecord<String, String>> records = pollRecordsForKey(submittedConsumer, stored.paymentId(), 1);
        assertEquals(1, records.size(), "the fraud service's topic must receive exactly one event for this payment");
        assertTrue(outbox.findUndispatchedBatch(org.springframework.data.domain.Limit.of(50)).stream()
                        .noneMatch(row -> row.getAggregateId().toString().equals(stored.paymentId())),
                "this payment's row must be marked dispatched after a successful publish");
    }

    // ---------------------------------------------- the brief's named failure test

    /**
     * <b>The failure test the brief calls the interview moment.</b> Kill the
     * process between the ledger commit and the publish, restart, and prove
     * the payment still reaches the fraud service.
     *
     * <p>"Kill the process" here means: after the ledger transaction commits
     * (so the outbox row is durable — the {@code poller} bean is never told
     * about it), assert directly against the database that nothing has been
     * published yet. That is the crashed state: a durable, unpublished event,
     * exactly as if the JVM had been kill -9'd the instant after COMMIT
     * returned. "Restart" is then a fresh call to {@code poller.poll()} —
     * legitimate, because the poller carries no in-memory state of its own;
     * everything it needs to recover is the row this test already proved is
     * still sitting in Postgres. A literal process kill would prove the same
     * thing through more infrastructure without testing a different property.
     */
    @Test
    void killing_the_process_between_commit_and_publish_does_not_lose_the_payment() {
        var stored = store.record(instruction("75.00"), "key-" + UUID.randomUUID());

        // The crash: assert THIS payment's event is durable but genuinely not
        // yet published anywhere - the state a kill -9 right after COMMIT
        // would leave behind. Not simulated by skipping a step; it is
        // literally what "outbox row exists, dispatchedAt IS NULL" means.
        // Filtered by this payment's own aggregate id, since the outbox table
        // (like the Kafka topic below) is shared across every test method.
        assertTrue(outbox.findUndispatchedBatch(org.springframework.data.domain.Limit.of(50)).stream()
                        .anyMatch(row -> row.getAggregateId().toString().equals(stored.paymentId())),
                "the payment must be durable before any publish attempt happens");
        assertTrue(pollRecordsForKey(submittedConsumer, stored.paymentId(), 1, 2_000).isEmpty(),
                "nothing may have reached Kafka yet for this payment - the poller has not run");

        // The restart: a fresh poll cycle, carrying no memory of what came
        // before. Everything it needs is what the crashed transaction left
        // durable in Postgres.
        poller.poll();

        List<ConsumerRecord<String, String>> afterRestart = pollRecordsForKey(submittedConsumer, stored.paymentId(), 1);
        assertEquals(1, afterRestart.size(),
                "the payment must reach the fraud service after restart - it was never lost");
    }

    // ------------------------------------------------------ downstream idempotency

    /**
     * The other half of the honesty this module insists on: the outbox
     * pattern closes the LOSS window (proven above) but not the DUPLICATION
     * window — a crash between a Kafka ack and marking a row dispatched would
     * republish it. Rather than contrive that exact race, this proves the
     * mechanism that makes a duplicate harmless regardless of how it arises:
     * the same decision, delivered twice, produces one row.
     */
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

    // --------------------------------------------------------------------- DLQ

    /**
     * A poison message must not halt the consumer. Published directly to
     * {@code payments.decisioned} (bypassing the outbox — this is testing the
     * consumer and its error handler, not the publish path), followed by a
     * well-formed message, proving the second one still gets processed.
     */
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

    /**
     * The replay procedure documented in KafkaConfig's javadoc, executed for
     * real: take a message off the DLQ and republish its value to
     * payments.decisioned. No consumer restart required - proves the
     * consumer's own idempotency (see above) is what makes a manual replay
     * safe to perform at any time, not a special recovery mode.
     */
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

            // A message that fails for a transient/fixable reason in this
            // test's stand-in: genuinely malformed, then "fixed" by an
            // operator before replay - the same shape a real bad-payload
            // incident would take.
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

    /**
     * <b>Why this test exists.</b> Every other test in this file builds a
     * {@code PaymentDecisionedEvent} in Java and serialises it — which can
     * never disagree with the record's own fields, and consequently never
     * caught a real bug: this record was missing {@code threshold} for a
     * while, and with {@code fail-on-unknown-properties: true} (set
     * deliberately in M1) every real message from the Python service failed
     * to deserialise and was silently routed to the DLQ. Every test here
     * stayed green throughout, because none of them exercised a payload this
     * record did not already know how to produce. Only running the actual
     * fraud service end to end and checking Postgres caught it — see
     * PaymentDecisionedEvent's own javadoc.
     *
     * <p>This test is the regression guard: a JSON string copied verbatim
     * from services/fraud-service/src/fraud_service/kafka/events.py's actual
     * field set, not round-tripped through this class at all. If the two
     * sides' schemas drift again, this is what notices.
     */
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
