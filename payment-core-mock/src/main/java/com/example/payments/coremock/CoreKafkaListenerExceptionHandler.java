package com.example.payments.coremock;

import io.micronaut.configuration.kafka.exceptions.DefaultKafkaListenerExceptionHandler;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerExceptionHandler;
import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a failed Core command at its current offset. A later phase can route it to a
 * durable retry/DLQ policy; this simulator must never commit it silently.
 */
@Singleton
@Replaces(DefaultKafkaListenerExceptionHandler.class)
final class CoreKafkaListenerExceptionHandler implements KafkaListenerExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CoreKafkaListenerExceptionHandler.class);

    @Override
    public void handle(KafkaListenerException exception) {
        exception.getConsumerRecord().ifPresent(record -> {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            exception.getKafkaConsumer().seek(partition, record.offset());
            LOG.error(
                    "Core command failed and remains uncommitted topic={} partition={} offset={} key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception.getCause());
        });
    }
}
