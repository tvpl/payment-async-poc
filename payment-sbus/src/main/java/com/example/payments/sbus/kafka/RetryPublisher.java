package com.example.payments.sbus.kafka;

import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.retry.DurableDeadLetterScheduler;
import com.example.payments.sbus.retry.DurableRetryScheduler;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Map;

/**
 * Persists failed records for due-based retry (or sends to DLQ once attempts are exhausted).
 *
 * <p>Both {@link DurableRetryScheduler} and {@link DurableDeadLetterScheduler} write to
 * Postgres — the same dependency whose own outage is often <em>why</em> the original record
 * failed in the first place (see {@code DependencyPolicies}, which declares POSTGRESQL's
 * recoverable state as {@code KAFKA_RECORD}: the record itself, still on the topic, is meant to
 * be the recovery path). If persisting the failure *also* fails, every method here logs the raw
 * record at ERROR before rethrowing, so the consumer's {@code @ErrorStrategy} keeps retrying
 * the whole record (it is never acknowledged) for its full budget — 900 attempts at 2s, 30
 * minutes, calibrated to outlast a realistic Postgres failover or restart rather than give up on
 * an ordinary blip. Only past that point does the offset advance and this stops being automatic:
 * the {@code sbus_unrecoverable_message_total} metric fires, and the logged payload (base64) is
 * a human's only remaining path to replay the record by hand.
 */
@Singleton
public class RetryPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPublisher.class);

    private final DurableRetryScheduler scheduler;
    private final DurableDeadLetterScheduler deadLetters;
    private final RetryProperties properties;
    private final SbusMetrics metrics;

    public RetryPublisher(DurableRetryScheduler scheduler,
                          DurableDeadLetterScheduler deadLetters,
                          RetryProperties properties,
                          SbusMetrics metrics) {
        this.scheduler = scheduler;
        this.deadLetters = deadLetters;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** First failure on the main topic → schedule attempt #1 on the retry topic. */
    public void scheduleFirstRetry(String originTopic, ConsumerRecord<String, byte[]> source,
                                   Map<String, String> headers, Throwable cause) {
        persistOrPreserve(originTopic, source, cause,
                () -> scheduler.schedule(originTopic, source, headers, 1, cause));
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
        persistOrPreserve(originTopic, source, cause,
                () -> scheduler.schedule(originTopic, source, headers, currentAttempt + 1, cause));
        return false;
    }

    public void routeToDlq(String originTopic, ConsumerRecord<String, byte[]> source,
                           Map<String, String> headers, Throwable cause, String stage) {
        persistOrPreserve(originTopic, source, cause,
                () -> deadLetters.schedule(originTopic, source, headers, cause, stage));
    }

    /**
     * Runs a durable-persistence attempt; if it throws, the original record's payload is logged
     * (so an operator can recover it manually) before the persistence failure is rethrown to the
     * caller — which lets the consumer's {@code @ErrorStrategy} keep retrying the whole record
     * rather than acknowledging it on a persistence failure.
     */
    private void persistOrPreserve(String originTopic, ConsumerRecord<String, byte[]> source,
                                   Throwable originalCause, Runnable persist) {
        try {
            persist.run();
        } catch (RuntimeException persistenceFailure) {
            metrics.recordUnrecoverable();
            LOG.error("SBUS_MESSAGE_AT_RISK durable persistence failed for a record whose own "
                    + "handling already failed — origin={} key={} partition={} offset={} "
                    + "payloadBase64={} originalCause={} persistenceFailure={}",
                    originTopic, source.key(), source.partition(), source.offset(),
                    Base64.getEncoder().encodeToString(source.value()),
                    originalCause, persistenceFailure, persistenceFailure);
            throw persistenceFailure;
        }
    }
}
