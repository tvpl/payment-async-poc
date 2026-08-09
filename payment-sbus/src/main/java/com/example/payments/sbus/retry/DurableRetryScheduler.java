package com.example.payments.sbus.retry;

import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.outbox.BackoffCalculator;
import com.example.payments.sbus.repository.OutboxEventRepository;
import com.example.payments.sbus.support.Json;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists retry bytes and headers in the outbox before the consumer offset can commit. */
@Singleton
public class DurableRetryScheduler {

    private final OutboxEventRepository repository;
    private final RetryProperties properties;
    private final Json json;
    private final Clock clock;

    @Inject
    public DurableRetryScheduler(OutboxEventRepository repository,
                                 RetryProperties properties,
                                 Json json) {
        this(repository, properties, json, Clock.systemUTC());
    }

    DurableRetryScheduler(OutboxEventRepository repository,
                          RetryProperties properties,
                          Json json,
                          Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ScheduleResult schedule(String originTopic,
                                   ConsumerRecord<String, byte[]> source,
                                   Map<String, String> sourceHeaders,
                                   int attempt,
                                   Throwable cause) {
        Duration delay = BackoffCalculator.backoff(
                attempt, properties.getBaseDelay(), properties.getMaxDelay());
        Instant dueAt = clock.instant().plus(delay);
        Map<String, String> headers = new LinkedHashMap<>(sourceHeaders);
        headers.put(Headers.ORIGIN_TOPIC, originTopic);
        headers.put(Headers.RETRY_ATTEMPT, String.valueOf(attempt));
        headers.put(Headers.RETRY_NOT_BEFORE, String.valueOf(dueAt.toEpochMilli()));
        headers.put("x-retry-reason", String.valueOf(cause == null ? "retry" : cause.getMessage()));

        String deduplicationKey = deduplicationKey(source, attempt);
        int inserted = repository.insertDurableRetry(
                source.key(), retryTopicFor(originTopic), source.key(), source.value(),
                json.toJson(headers), dueAt, deduplicationKey);
        return new ScheduleResult(inserted == 1, dueAt, attempt, deduplicationKey);
    }

    static String deduplicationKey(ConsumerRecord<?, ?> source, int attempt) {
        return "retry:" + source.topic() + ':' + source.partition() + ':' + source.offset() + ':' + attempt;
    }

    static String retryTopicFor(String originTopic) {
        return switch (originTopic) {
            case Topics.REQUESTED -> Topics.REQUESTED_RETRY;
            case Topics.CORE_RESPONSE -> Topics.CORE_RESPONSE_RETRY;
            default -> throw new IllegalArgumentException("Unsupported retry origin topic: " + originTopic);
        };
    }

    public record ScheduleResult(boolean inserted, Instant dueAt, int attempt,
                                 String deduplicationKey) {
    }
}
