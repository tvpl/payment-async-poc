package com.example.payments.sbus.kafka;

import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Consumes {@code PaymentSimulationRequested} (Avro bytes). Offsets are committed
 * per record only after the method returns normally; we always return normally:
 * either the work succeeded, or — after bounded in-process retries for transient
 * errors, or immediately for poison messages — the record was routed to the DLQ.
 * Nothing is ever silently skipped.
 */
@KafkaListener(
        groupId = "payment-sbus",
        offsetReset = OffsetReset.EARLIEST,
        offsetStrategy = OffsetStrategy.SYNC_PER_RECORD)
public class PaymentRequestedConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentRequestedConsumer.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final PaymentSimulationService service;
    private final AvroSerde avroSerde;
    private final KafkaPublisher publisher;
    private final SbusMetrics metrics;

    public PaymentRequestedConsumer(PaymentSimulationService service,
                                    AvroSerde avroSerde,
                                    KafkaPublisher publisher,
                                    SbusMetrics metrics) {
        this.service = service;
        this.avroSerde = avroSerde;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Topic(Topics.REQUESTED)
    public void receive(ConsumerRecord<String, byte[]> record) {
        EventEnvelope<PaymentSimulationRequestPayload> env;
        try {
            PaymentSimulationRequested avro = avroSerde.deserialize(record.topic(), record.value());
            env = AvroMapper.fromAvro(avro);
            validate(env);
        } catch (Exception parseError) {
            sendToDlq(record, parseError, "deserialize/validate");
            return;
        }
        try {
            Mdc.fromConsumer(record, env);
            String idempotencyKey = header(record, Headers.IDEMPOTENCY_KEY);
            String traceparent = header(record, Headers.TRACEPARENT);
            Retries.run(MAX_ATTEMPTS, RETRY_DELAY,
                    () -> service.handleRequested(env, idempotencyKey, traceparent));
        } catch (RuntimeException processingError) {
            // Transient retries exhausted -> DLQ so the offset can advance without loss.
            sendToDlq(record, processingError, "processing");
        } finally {
            Mdc.clear();
        }
    }

    private void validate(EventEnvelope<PaymentSimulationRequestPayload> env) {
        if (env == null || env.requestId() == null || env.payload() == null) {
            throw new PoisonMessageException("Invalid envelope: missing requestId/payload", null);
        }
        PaymentSimulationRequestPayload p = env.payload();
        if (p.amount() == null || p.merchantId() == null || p.currency() == null) {
            throw new PoisonMessageException("Invalid payload: missing required fields", null);
        }
    }

    private void sendToDlq(ConsumerRecord<String, byte[]> record, Exception cause, String stage) {
        LOG.error("Routing PaymentSimulationRequested to DLQ stage={} key={} offset={}",
                stage, record.key(), record.offset(), cause);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-dlq-origin-topic", record.topic());
        headers.put("x-dlq-stage", stage);
        headers.put("x-dlq-reason", String.valueOf(cause.getMessage()));
        publisher.send(Topics.DLQ, record.key(), record.value(), headers);
        metrics.recordDlq();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
