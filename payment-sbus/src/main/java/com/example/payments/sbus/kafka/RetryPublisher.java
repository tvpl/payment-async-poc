package com.example.payments.sbus.kafka;

import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.retry.DurableDeadLetterScheduler;
import com.example.payments.sbus.retry.DurableRetryScheduler;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.Map;

/**
 * Persists failed records for due-based retry (or sends to DLQ once attempts are exhausted).
 */
@Singleton
public class RetryPublisher {

    private final DurableRetryScheduler scheduler;
    private final DurableDeadLetterScheduler deadLetters;
    private final RetryProperties properties;

    public RetryPublisher(DurableRetryScheduler scheduler,
                          DurableDeadLetterScheduler deadLetters,
                          RetryProperties properties) {
        this.scheduler = scheduler;
        this.deadLetters = deadLetters;
        this.properties = properties;
    }

    /** First failure on the main topic → schedule attempt #1 on the retry topic. */
    public void scheduleFirstRetry(String originTopic, ConsumerRecord<String, byte[]> source,
                                   Map<String, String> headers, Throwable cause) {
        scheduler.schedule(originTopic, source, headers, 1, cause);
    }

    /**
     * Subsequent failure on the retry topic → next attempt, or DLQ once exhausted.
     * @return true if routed to DLQ (terminal)
     */
    public boolean scheduleNextOrDlq(String originTopic, ConsumerRecord<String, byte[]> source,
                                     Map<String, String> headers, int currentAttempt, Throwable cause) {
        if (currentAttempt >= properties.getMaxAttempts()) {
            routeToDlq(originTopic, source, headers, cause, "retries-exhausted");
            return true;
        }
        scheduler.schedule(originTopic, source, headers, currentAttempt + 1, cause);
        return false;
    }

    public void routeToDlq(String originTopic, ConsumerRecord<String, byte[]> source,
                           Map<String, String> headers, Throwable cause, String stage) {
        deadLetters.schedule(originTopic, source, headers, cause, stage);
    }
}
