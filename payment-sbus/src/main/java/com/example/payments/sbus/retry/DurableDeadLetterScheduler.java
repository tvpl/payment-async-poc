package com.example.payments.sbus.retry;

import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists poison/exhausted records as recoverable DLQ work before offset commit. */
@Singleton
public class DurableDeadLetterScheduler {

    private final OutboxEventRepository repository;
    private final Json json;

    public DurableDeadLetterScheduler(OutboxEventRepository repository, Json json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public ScheduleResult schedule(String originTopic,
                                   ConsumerRecord<String, byte[]> source,
                                   Map<String, String> sourceHeaders,
                                   Throwable cause,
                                   String stage) {
        Map<String, String> headers = new LinkedHashMap<>(sourceHeaders);
        headers.put("x-dlq-origin-topic", originTopic);
        headers.put("x-dlq-stage", stage);
        headers.put("x-dlq-reason", String.valueOf(cause == null ? stage : cause.getMessage()));
        String deduplicationKey = deduplicationKey(source, stage);
        String messageKey = source.key() == null || source.key().isBlank()
                ? source.topic() + ':' + source.partition() + ':' + source.offset()
                : source.key();
        Instant dueAt = Instant.now();
        int inserted = repository.insertDurableDeadLetter(
                messageKey, messageKey, source.value(), json.toJson(headers),
                dueAt, deduplicationKey);
        return new ScheduleResult(inserted == 1, deduplicationKey);
    }

    static String deduplicationKey(ConsumerRecord<?, ?> source, String stage) {
        return "dlq:" + source.topic() + ':' + source.partition() + ':'
                + source.offset() + ':' + stage;
    }

    public record ScheduleResult(boolean inserted, String deduplicationKey) {
    }
}
