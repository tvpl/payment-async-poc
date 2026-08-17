package com.example.payments.sbus.kafka;

import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task_T37 (SCAL-01): supersedes the pre-T37 30-minute retry-budget policy. The
 * {@code @ErrorStrategy} on every consumer here only actually retries when
 * {@link RetryPublisher}'s own durable write fails — in practice, Postgres itself being down
 * (see its javadoc). That budget used to be 900 x 2s = 30 minutes, which meant
 * {@code max.poll.interval.ms} had to stay just as large (35 minutes) so the consumer group would
 * not evict the instance mid-retry — holding the partition hostage for up to that long during a
 * prolonged outage. SCAL-01 caps {@code max.poll.interval.ms} at <=5 minutes instead, which means
 * the in-process retry budget must stay small enough that even a full {@code max.poll.records}
 * batch (100) hitting it simultaneously never approaches that ceiling — asserting the annotation's
 * literal values (rather than actually waiting out a live retry loop in an IT) is the only
 * practical way to prove the budget without a test that takes minutes to run.
 */
class ConsumerErrorStrategyUnitTest {

    private static final Duration MAX_POLL_INTERVAL_CEILING = Duration.ofMinutes(5);
    /** Safety margin under the ceiling for the worst case of a full batch failing at once. */
    private static final Duration WORST_CASE_FULL_BATCH_BUDGET = Duration.ofMinutes(3);

    @Test
    void maxPollIntervalMsOnTheDefaultConsumerStaysAtOrBelowTheFiveMinuteCeiling() throws Exception {
        long maxPollIntervalMs = readMaxPollIntervalMs();

        assertTrue(maxPollIntervalMs <= MAX_POLL_INTERVAL_CEILING.toMillis(),
                "kafka.consumers.default.max.poll.interval.ms is " + maxPollIntervalMs
                        + "ms, above the SCAL-01 ceiling of " + MAX_POLL_INTERVAL_CEILING);
    }

    @Test
    void paymentRequestedConsumerRetryBudgetNeverBlocksTheConsumeLoopForLong() {
        assertPerRecordBudgetIsShort(PaymentRequestedConsumer.class);
    }

    @Test
    void coreResponseConsumerRetryBudgetNeverBlocksTheConsumeLoopForLong() {
        assertPerRecordBudgetIsShort(CoreResponseConsumer.class);
    }

    @Test
    void retryConsumerRetryBudgetNeverBlocksTheConsumeLoopForLong() {
        assertPerRecordBudgetIsShort(RetryConsumer.class);
    }

    /**
     * task_T15 (AUD-10): {@code PaymentRequestedConsumer} and {@code CoreResponseConsumer} used
     * to share the {@code payment-sbus} consumer group while consuming different topics — a
     * rebalance triggered by either listener revoked the other's partition assignments too, even
     * though they have nothing to do with each other's topic.
     */
    @Test
    void paymentRequestedAndCoreResponseConsumersUseDistinctGroupIds() {
        String requestedGroupId = PaymentRequestedConsumer.class.getAnnotation(KafkaListener.class).groupId();
        String coreResponseGroupId = CoreResponseConsumer.class.getAnnotation(KafkaListener.class).groupId();

        assertNotEquals(requestedGroupId, coreResponseGroupId,
                "a rebalance on one listener must never revoke the other's unrelated partitions");
        assertEquals("payment-sbus-requested", requestedGroupId);
        assertEquals("payment-sbus-core-response", coreResponseGroupId);
    }

    /**
     * task_T37 (SCAL-01): even the worst case — every record in a full {@code max.poll.records}
     * batch (100) hitting the retry-budget path at once — must still fit comfortably under
     * {@code max.poll.interval.ms}, or the very first batch of a real outage would breach the
     * ceiling this policy exists to enforce.
     */
    @Test
    void aFullPollRecordsBatchAllHittingTheRetryBudgetAtOnceStillFitsUnderTheInterval() throws Exception {
        long maxPollRecords = readMaxPollRecords();
        long maxPollIntervalMs = readMaxPollIntervalMs();

        for (Class<?> consumer : List.of(
                PaymentRequestedConsumer.class, CoreResponseConsumer.class, RetryConsumer.class)) {
            Duration perRecordBudget = perRecordBudget(consumer);
            Duration worstCaseFullBatch = perRecordBudget.multipliedBy(maxPollRecords);

            assertTrue(worstCaseFullBatch.toMillis() < maxPollIntervalMs,
                    consumer.getSimpleName() + "'s worst-case full-batch budget is "
                            + worstCaseFullBatch + " (" + maxPollRecords + " records x "
                            + perRecordBudget + "), which must stay under max.poll.interval.ms ("
                            + maxPollIntervalMs + "ms)");
        }
    }

    private static void assertPerRecordBudgetIsShort(Class<?> consumer) {
        Duration budget = perRecordBudget(consumer);

        assertTrue(budget.compareTo(WORST_CASE_FULL_BATCH_BUDGET) < 0,
                consumer.getSimpleName() + "'s per-record retry budget is " + budget
                        + ", which is no longer short (SCAL-01 requires it stay small enough that "
                        + "even a full poll-records batch fits under max.poll.interval.ms)");
    }

    private static Duration perRecordBudget(Class<?> consumer) {
        KafkaListener listener = consumer.getAnnotation(KafkaListener.class);
        ErrorStrategy strategy = listener.errorStrategy();
        Duration delay = parseDurationLiteral(strategy.retryDelay());
        return delay.multipliedBy(strategy.retryCount());
    }

    /**
     * {@code retryDelay} uses Micronaut's own duration literal ({@code "250ms"}, {@code "2s"}),
     * not ISO-8601 — {@code Duration.parse} does not accept a bare {@code "ms"} suffix.
     */
    private static Duration parseDurationLiteral(String value) {
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        throw new IllegalArgumentException("Unsupported duration literal: " + value);
    }

    private static long readMaxPollIntervalMs() throws Exception {
        Map<?, ?> interval = asMap(pollConfig().get("interval"));
        return ((Number) interval.get("ms")).longValue();
    }

    private static long readMaxPollRecords() throws Exception {
        return ((Number) pollConfig().get("records")).longValue();
    }

    private static Map<?, ?> pollConfig() throws Exception {
        try (InputStream yaml = ConsumerErrorStrategyUnitTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(yaml, "application.yml must be on the classpath");
            Map<String, Object> root = new Yaml().load(yaml);
            Map<?, ?> kafka = asMap(root.get("kafka"));
            Map<?, ?> consumers = asMap(kafka.get("consumers"));
            Map<?, ?> defaultConsumer = asMap(consumers.get("default"));
            Map<?, ?> max = asMap(defaultConsumer.get("max"));
            return asMap(max.get("poll"));
        }
    }

    private static Map<?, ?> asMap(Object value) {
        return (Map<?, ?>) value;
    }
}
