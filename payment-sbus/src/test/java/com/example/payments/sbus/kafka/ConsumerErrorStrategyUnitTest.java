package com.example.payments.sbus.kafka;

import io.micronaut.configuration.kafka.annotation.ErrorStrategy;
import io.micronaut.configuration.kafka.annotation.KafkaListener;

import java.time.Duration;

import org.junit.jupiter.api.Test;

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
