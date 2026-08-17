package com.example.payments.sbus.kafka;

import com.example.payments.sbus.config.RetryProperties;
import com.example.payments.sbus.metrics.SbusMetrics;
import com.example.payments.sbus.retry.DurableDeadLetterScheduler;
import com.example.payments.sbus.retry.DurableRetryScheduler;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persists failed records for due-based retry (or sends to DLQ once attempts are exhausted).
 *
 * <p>Both {@link DurableRetryScheduler} and {@link DurableDeadLetterScheduler} write to
 * Postgres — the same dependency whose own outage is often <em>why</em> the original record
 * failed in the first place (see {@code DependencyPolicies}, which declares POSTGRESQL's
 * recoverable state as {@code KAFKA_RECORD}: the record itself, still on the topic, is meant to
 * be the recovery path). If persisting the failure *also* fails, every method here logs a
 * <strong>pointer only</strong> (SEC-02: {@code topic/partition/offset/key}, never the payload in
 * clear text or base64) at ERROR before rethrowing, so the consumer's {@code @ErrorStrategy}
 * keeps retrying the whole record (it is never acknowledged) for its full budget — a short one
 * (SCAL-01: a handful of attempts at a few hundred ms, not the 30-minute budget task_T15/AUD-10
 * originally used), deliberately small so a prolonged Postgres outage never holds this consumer's
 * partition hostage. Only past that point does the offset advance and this stops being
 * automatic: the {@code sbus_unrecoverable_message_total} metric fires, and the record — still on
 * its origin topic at that logged offset — is a human's path to replay it by hand.
 */
@Singleton
public class RetryPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RetryPublisher.class);

    /** SEC-03: risk-header values are truncated so a persisted reason cannot grow unbounded. */
    private static final int MAX_REASON_LENGTH = 500;
    /** SEC-03: a long base64/hex-like run reads as embedded payload content, not a human reason. */
    private static final Pattern PAYLOAD_LIKE_RUN = Pattern.compile("[A-Za-z0-9+/_-]{40,}={0,2}");

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
        Map<String, String> sanitized = sanitizeHeaders(headers);
        persistOrPreserve(originTopic, source, cause,
                () -> scheduler.schedule(originTopic, source, sanitized, 1, cause));
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
        Map<String, String> sanitized = sanitizeHeaders(headers);
        persistOrPreserve(originTopic, source, cause,
                () -> scheduler.schedule(originTopic, source, sanitized, currentAttempt + 1, cause));
        return false;
    }

    public void routeToDlq(String originTopic, ConsumerRecord<String, byte[]> source,
                           Map<String, String> headers, Throwable cause, String stage) {
        Map<String, String> sanitized = sanitizeHeaders(headers);
        persistOrPreserve(originTopic, source, cause,
                () -> deadLetters.schedule(originTopic, source, sanitized, cause, stage));
    }

    /**
     * Runs a durable-persistence attempt; if it throws, a pointer to the original record (SEC-02:
     * topic/partition/offset/key — never the payload) is logged before the persistence failure is
     * rethrown to the caller — which lets the consumer's {@code @ErrorStrategy} keep retrying the
     * whole record rather than acknowledging it on a persistence failure. The record itself stays
     * recoverable from Kafka at that exact pointer for as long as the retry budget keeps the
     * offset from advancing.
     */
    private void persistOrPreserve(String originTopic, ConsumerRecord<String, byte[]> source,
                                   Throwable originalCause, Runnable persist) {
        try {
            persist.run();
        } catch (RuntimeException persistenceFailure) {
            metrics.recordUnrecoverable();
            LOG.error("SBUS_MESSAGE_AT_RISK durable persistence failed for a record whose own "
                    + "handling already failed — origin={} key={} partition={} offset={} "
                    + "originalCause={} persistenceFailure={}",
                    originTopic, source.key(), source.partition(), source.offset(),
                    originalCause, persistenceFailure, persistenceFailure);
            throw persistenceFailure;
        }
    }

    /**
     * SEC-03: sanitizes risk-carrying header values already present on an incoming record (e.g.
     * {@code x-retry-reason}/{@code x-dlq-reason} inherited from an earlier retry hop) before they
     * are forwarded to be persisted again — truncated and with any long payload-like run redacted.
     */
    static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> sanitized = new LinkedHashMap<>(headers);
        sanitized.computeIfPresent("x-retry-reason", (key, value) -> sanitizeReason(value));
        sanitized.computeIfPresent("x-dlq-reason", (key, value) -> sanitizeReason(value));
        return sanitized;
    }

    /** SEC-03: truncates and strips payload-like content from a persisted exception message. */
    public static String sanitizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String redacted = PAYLOAD_LIKE_RUN.matcher(reason).replaceAll("[redacted]");
        return redacted.length() > MAX_REASON_LENGTH ? redacted.substring(0, MAX_REASON_LENGTH) : redacted;
    }
}
