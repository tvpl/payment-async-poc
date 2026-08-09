package com.example.payments.sbus.support;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts Kafka record headers to a String map for the handler/retry/DLQ paths. */
public final class KafkaHeaders {

    private KafkaHeaders() {
    }

    public static Map<String, String> toMap(ConsumerRecord<?, ?> record) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Header h : record.headers()) {
            if (h.value() != null) {
                map.put(h.key(), new String(h.value(), StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
