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

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition("payments.dlq", record.partition()));

        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, DefaultErrorHandler kafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
