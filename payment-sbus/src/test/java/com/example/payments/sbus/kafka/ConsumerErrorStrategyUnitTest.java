package com.example.payments.sbus.kafka;

import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code @ErrorStrategy} on every consumer here only actually retries when
 * {@link RetryPublisher}'s own durable write fails — in practice, Postgres itself being down
 * (see its javadoc). That budget used to be 50 x 2s = 100s, nowhere near enough to outlast an
 * ordinary Postgres failover or restart. Asserting the annotation's literal values (rather than
 * actually waiting out a multi-minute retry loop in a live IT) is the only practical way to
 * prove the budget without a test that takes 30 minutes to run.
 */
class ConsumerErrorStrategyUnitTest {

    private static final Duration MINIMUM_BUDGET = Duration.ofMinutes(30);

    @Test
    void paymentRequestedConsumerRetriesForAtLeastThirtyMinutes() {
        assertBudgetAtLeastThirtyMinutes(PaymentRequestedConsumer.class);
    }

    @Test
    void coreResponseConsumerRetriesForAtLeastThirtyMinutes() {
        assertBudgetAtLeastThirtyMinutes(CoreResponseConsumer.class);
    }

    @Test
    void retryConsumerRetriesForAtLeastThirtyMinutes() {
        assertBudgetAtLeastThirtyMinutes(RetryConsumer.class);
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
     * task_T15 (AUD-10): {@code max.poll.interval.ms} was unset, defaulting to Kafka's own 5
     * minutes — far below the 30-minute retry budget (retryCount=900 x retryDelay=2s, asserted
     * above) a durable-persistence retry loop needs to ride out a realistic Postgres failover.
     * Below the budget, the consumer group would evict the instance for exceeding
     * max.poll.interval.ms long before the retries against a down Postgres exhaust — this is a
     * YAML-configured value (not annotation-driven like the error strategy above), so it is
     * asserted by parsing the actual application.yml the app boots with.
     */
    @Test
    void maxPollIntervalMsOnTheDefaultConsumerExceedsTheThirtyMinuteRetryBudget() throws Exception {
        try (InputStream yaml = ConsumerErrorStrategyUnitTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(yaml, "application.yml must be on the classpath");
            Map<String, Object> root = new Yaml().load(yaml);
            Map<?, ?> kafka = asMap(root.get("kafka"));
            Map<?, ?> consumers = asMap(kafka.get("consumers"));
            Map<?, ?> defaultConsumer = asMap(consumers.get("default"));
            Map<?, ?> max = asMap(defaultConsumer.get("max"));
            Map<?, ?> poll = asMap(max.get("poll"));
            Map<?, ?> interval = asMap(poll.get("interval"));
            long maxPollIntervalMs = ((Number) interval.get("ms")).longValue();

            assertTrue(maxPollIntervalMs > MINIMUM_BUDGET.toMillis(),
                    "kafka.consumers.default.max.poll.interval.ms is " + maxPollIntervalMs
                            + "ms, below the " + MINIMUM_BUDGET + " retry floor — the consumer "
                            + "group would evict the instance before the retries exhaust");
        }
    }

    private static Map<?, ?> asMap(Object value) {
        return (Map<?, ?>) value;
    }

    private static void assertBudgetAtLeastThirtyMinutes(Class<?> consumer) {
        KafkaListener listener = consumer.getAnnotation(KafkaListener.class);
        ErrorStrategy strategy = listener.errorStrategy();

        Duration delay = Duration.parse("PT" + strategy.retryDelay().toUpperCase());
        Duration budget = delay.multipliedBy(strategy.retryCount());

        assertTrue(budget.compareTo(MINIMUM_BUDGET) >= 0,
                consumer.getSimpleName() + "'s retry budget is " + budget
                        + " (retryCount=" + strategy.retryCount() + " x retryDelay=" + delay
                        + "), below the " + MINIMUM_BUDGET + " floor needed to outlast a "
                        + "realistic Postgres failover or restart");
    }
}
