package com.example.payments.api.kafka;

import com.example.payments.common.events.Topics;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.messaging.annotation.MessageHeader;

/**
 * Publishes a final event the API could not apply to the dead-letter topic, preserving the
 * original bytes so it stays recoverable. {@code acks=ALL}: the record is only considered
 * dead-lettered once the broker confirms it.
 */
@KafkaClient(id = "payment-api-dlq", acks = KafkaClient.Acknowledge.ALL)
public interface PaymentResponseDlqProducer {

    @Topic(Topics.DLQ)
    void send(@KafkaKey @Nullable String key,
              @MessageHeader(ResponseDeadLetters.ORIGIN_TOPIC) String originTopic,
              @MessageHeader(ResponseDeadLetters.STAGE) String stage,
              @MessageHeader(ResponseDeadLetters.REASON) String reason,
              byte[] value);
}
