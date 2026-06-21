package com.example.payments.sbus.kafka;

import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.service.PaymentSimulationService;
import com.example.payments.sbus.support.Json;
import com.example.payments.sbus.support.Mdc;
import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.ErrorStrategyValue;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.core.type.Argument;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Consumes {@code PaymentSimulationRequested}. Permanently broken messages are
 * routed to the DLQ (committed); transient failures are rethrown so the
 * {@link ErrorStrategy} retries before the broker advances the offset.
 *
 * <p>Partitioned by {@code requestId} (the message key), so all events for one
 * simulation are processed in order by a single consumer in the group.
 */
@KafkaListener(
        groupId = "payment-sbus",
        offsetReset = OffsetReset.EARLIEST,
        errorStrategy = @ErrorStrategy(
                value = ErrorStrategyValue.RETRY_ON_ERROR,
                retryCount = 3,
                retryDelay = "1s"))
public class PaymentRequestedConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentRequestedConsumer.class);
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Argument<EventEnvelope<PaymentSimulationRequestPayload>> TYPE =
            (Argument) Argument.of(EventEnvelope.class, PaymentSimulationRequestPayload.class);

    private final PaymentSimulationService service;
    private final Json json;
    private final KafkaPublisher publisher;
    private final SbusMetrics metrics;

    public PaymentRequestedConsumer(PaymentSimulationService service,
                                    Json json,
                                    KafkaPublisher publisher,
                                    SbusMetrics metrics) {
        this.service = service;
        this.json = json;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Topic(Topics.REQUESTED)
    public void receive(ConsumerRecord<String, String> record) {
        EventEnvelope<PaymentSimulationRequestPayload> env;
        try {
            env = json.fromJson(record.value(), TYPE);
            validate(env);
        } catch (Exception parseError) {
            sendToDlq(record, parseError);
            return;
        }
        try {
            Mdc.fromConsumer(record, env);
            String idempotencyKey = header(record, Headers.IDEMPOTENCY_KEY);
            String traceparent = header(record, Headers.TRACEPARENT);
            service.handleRequested(env, idempotencyKey, traceparent);
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

    private void sendToDlq(ConsumerRecord<String, String> record, Exception cause) {
        LOG.error("Routing poison PaymentSimulationRequested to DLQ key={} offset={}",
                record.key(), record.offset(), cause);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-dlq-origin-topic", record.topic());
        headers.put("x-dlq-reason", cause.getMessage());
        publisher.send(Topics.DLQ, record.key(), record.value(), headers);
        metrics.recordDlq();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
