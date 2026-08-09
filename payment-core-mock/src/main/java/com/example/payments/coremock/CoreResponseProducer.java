package com.example.payments.coremock;

import com.example.payments.common.events.Topics;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.messaging.annotation.MessageHeader;

@KafkaClient(id = "core-response-producer", acks = KafkaClient.Acknowledge.ALL)
public interface CoreResponseProducer {

    @Topic(Topics.CORE_RESPONSE)
    void send(@KafkaKey String key,
              @MessageHeader("x-request-id") String requestId,
              @MessageHeader("traceparent") String traceparent,
              byte[] value);
}
