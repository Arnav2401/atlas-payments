package com.atlas.payments.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling // OutboxPoller
@EnableKafka       // @KafkaListener processing
public class KafkaConfig {

    /**
     * Three partitions: enough for the ordering guarantee that matters (every
     * event for one payment — keyed on its paymentId, see OutboxPoller —
     * lands on the same partition, so per-payment order is preserved) without
     * over-provisioning for a system that does not yet have the throughput to
     * need more. Auto-created by Spring Boot's KafkaAdmin from these beans;
     * production would provision topics deliberately instead, but that is an
     * M5-shaped concern, not this module's.
     */
    @Bean
    public NewTopic paymentsSubmittedTopic() {
        return TopicBuilder.name(PaymentSubmittedEvent.TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentsDecisionedTopic() {
        return TopicBuilder.name("payments.decisioned").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentsDlqTopic() {
        return TopicBuilder.name("payments.dlq").partitions(3).replicas(1).build();
    }

    /**
     * A poison message — one that fails to parse or throws for any other
     * reason — is retried twice, 1 second apart, then published verbatim to
     * {@code payments.dlq} (headers carry the original topic, partition,
     * offset and the exception, via Spring Kafka's default header naming) and
     * the offset is committed. That commit is what stops one bad message from
     * blocking every message behind it in the partition forever — the
     * "does not halt the consumer" requirement.
     *
     * <p>Fixed destination, not the recoverer's default "append -dlt to the
     * original topic" behaviour: the brief names a single {@code payments.dlq}
     * topic, and there is currently exactly one Java-side listener
     * ({@link com.atlas.payments.fraud.PaymentDecisionConsumer}), so one fixed
     * destination is simpler than a naming convention this system does not
     * yet need.
     *
     * <h2>Replay procedure</h2>
     *
     * A message on {@code payments.dlq} is not lost — it is a real message,
     * durably stored, that this consumer could not process. To replay it:
     * inspect it (its headers name the original exception and topic), fix
     * whatever made it unparseable or fix the bug that threw, then republish
     * its value to {@code payments.decisioned} — a consumer restart is not
     * required, since {@link com.atlas.payments.fraud.PaymentDecisionConsumer}
     * is idempotent on {@code payment_id} and a replayed message is handled
     * the same way any other redelivery is.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition("payments.dlq", record.partition()));

        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2));
    }

    /**
     * Named {@code kafkaListenerContainerFactory} deliberately — that is the
     * bean name {@code @KafkaListener} looks up by default when no explicit
     * {@code containerFactory} is given, so this replaces Spring Boot's
     * auto-configured factory rather than existing alongside it. The only
     * thing this adds over the default is the DLQ error handler above.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, DefaultErrorHandler kafkaErrorHandler) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
