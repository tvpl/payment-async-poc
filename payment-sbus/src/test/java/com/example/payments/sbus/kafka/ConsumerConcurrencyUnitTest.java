package com.example.payments.sbus.kafka;

import io.micronaut.configuration.kafka.annotation.KafkaListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * task_T39 (SCAL-03): each SBUS listener's consumer thread count is configurable (not a fixed
 * literal), with a default of 3, via Micronaut's own {@code ${...}} placeholder resolution on
 * {@code threadsValue} — the actual RESOLVED value at runtime depends on the running environment
 * (see {@code PaymentRequestedConsumerConcurrencyIT} for that, an IT since it needs a live
 * ApplicationContext to resolve placeholders). This unit test only proves the annotation itself
 * is wired to the RIGHT property key with the RIGHT default, without needing a container.
 */
class ConsumerConcurrencyUnitTest {

    @Test
    void paymentRequestedConsumerThreadsIsConfigurableWithDefaultThree() {
        assertThreadsPlaceholder(PaymentRequestedConsumer.class,
                "${sbus.kafka.consumers.requested.threads:3}");
    }

    @Test
    void coreResponseConsumerThreadsIsConfigurableWithDefaultThree() {
        assertThreadsPlaceholder(CoreResponseConsumer.class,
                "${sbus.kafka.consumers.core-response.threads:3}");
    }

    @Test
    void retryConsumerThreadsIsConfigurableWithDefaultThree() {
        assertThreadsPlaceholder(RetryConsumer.class,
                "${sbus.kafka.consumers.retry.threads:3}");
    }

    private static void assertThreadsPlaceholder(Class<?> consumer, String expected) {
        String threadsValue = consumer.getAnnotation(KafkaListener.class).threadsValue();
        assertEquals(expected, threadsValue,
                consumer.getSimpleName() + "'s threadsValue must resolve from a configurable "
                        + "property with a default of 3, not a fixed literal");
    }
}
