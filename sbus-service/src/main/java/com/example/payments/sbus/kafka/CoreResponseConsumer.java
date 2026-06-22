package com.example.payments.sbus.kafka;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.service.PaymentSimulationService;
import com.example.payments.sbus.support.Mdc;
import com.example.payments.sbus.support.Retries;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.configuration.kafka.annotation.Topic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Consumes the Core's response (Avro) and triggers the final completed/failed outbox event. */
@KafkaListener(
        groupId = "payment-sbus",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD)
public class CoreResponseConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(CoreResponseConsumer.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final PaymentSimulationService service;
    private final AvroSerde avroSerde;
    private final KafkaPublisher publisher;
    private final SbusMetrics metrics;

    public CoreResponseConsumer(PaymentSimulationService service,
                                AvroSerde avroSerde,
                                KafkaPublisher publisher,
                                SbusMetrics metrics) {
        this.service = service;
        this.avroSerde = avroSerde;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Topic(Topics.CORE_RESPONSE)
    public void receive(ConsumerRecord<String, byte[]> record) {
        EventEnvelope<CorePaymentSimulationResponsePayload> env;
        try {
            CorePaymentSimulationResponse avro = avroSerde.deserialize(record.topic(), record.value());
            env = AvroMapper.fromAvro(avro);
            if (env.payload() == null || env.payload().simulationId() == null) {
                throw new PoisonMessageException("Invalid Core response envelope", null);
            }
        } catch (Exception parseError) {
            sendToDlq(record, parseError, "deserialize/validate");
            return;
        }
        try {
            Mdc.fromConsumer(record, env);
            Retries.run(MAX_ATTEMPTS, RETRY_DELAY, () -> service.handleCoreResponse(env));
        } catch (RuntimeException processingError) {
            sendToDlq(record, processingError, "processing");
        } finally {
            Mdc.clear();
        }
    }

    private void sendToDlq(ConsumerRecord<String, byte[]> record, Exception cause, String stage) {
        LOG.error("Routing CoreResponse to DLQ stage={} offset={}", stage, record.offset(), cause);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-dlq-origin-topic", record.topic());
        headers.put("x-dlq-stage", stage);
        headers.put("x-dlq-reason", String.valueOf(cause.getMessage()));
        publisher.send(Topics.DLQ, record.key(), record.value(), headers);
        metrics.recordDlq();
    }
}
