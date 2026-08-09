package com.example.payments.sbus.support;

import com.example.payments.common.events.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;

/** Populates the logging MDC so structured JSON logs carry correlation fields. */
public final class Mdc {

    private Mdc() {
    }

    public static void fromConsumer(ConsumerRecord<?, ?> record, EventEnvelope<?> env) {
        if (env != null) {
            MDC.put("requestId", env.requestId());
            MDC.put("correlationId", env.correlationId());
            MDC.put("causationId", env.causationId());
            MDC.put("traceId", env.traceId());
            MDC.put("eventType", env.eventType());
        }
        MDC.put("topic", record.topic());
        MDC.put("partition", String.valueOf(record.partition()));
        MDC.put("offset", String.valueOf(record.offset()));
    }

    public static void clear() {
        MDC.clear();
    }
}
