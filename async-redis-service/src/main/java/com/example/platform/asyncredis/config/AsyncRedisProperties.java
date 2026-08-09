package com.example.platform.asyncredis.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;

import java.time.Duration;

/** Tunables for the Kafka-free async->sync flow. Bound from {@code async.redis.*}. */
@ConfigurationProperties("async.redis")
public class AsyncRedisProperties {

    private Duration waitTimeout = Duration.ofSeconds(3);
    private Duration resultTtl = Duration.ofMinutes(15);
    /**
     * How long an accepted job stays answerable. Must be at least {@code result-ttl}: the window
     * between the two is exactly what makes an expired result distinguishable from an unknown job.
     */
    private Duration statusTtl = Duration.ofHours(24);
    /** How long an idempotency key stays bound to its job. */
    private Duration idempotencyTtl = Duration.ofHours(24);
    /** Whether {@code POST /jobs} rejects a submission with no {@code Idempotency-Key}. */
    private boolean idempotencyRequired = false;
    private int workerConcurrency = 2;
    private String stream = "async.jobs";
    private String group = "workers";
    private long processLatencyMinMs = 20;
    private long processLatencyMaxMs = 150;
    private Duration reclaimIdle = Duration.ofSeconds(30);
    /** Approximate cap on stream length (XADD MAXLEN ~) to bound memory. */
    private long streamMaxlen = 100_000;
    /** Dead-letter stream for poison jobs. */
    private String dlqStream = "async.jobs.dlq";
    /** Max deliveries before a job is moved to the DLQ (poison protection). */
    private int maxDeliveries = 5;
    /** Admission rate limit for POST /jobs (per second, global via Redis). 0 disables it. */
    private int admissionLimitPerSec = 0;
    /** Max pooled connections for concurrent blocking BRPOP waits. */
    private int poolMaxTotal = 64;
    /**
     * Default ceiling on how long acquiring a wait connection may block. A request passes what is
     * left of its own budget instead; this is the backstop that keeps any other borrow finite.
     */
    private Duration poolMaxWait = Duration.ofSeconds(1);
    /** How often a worker scans pending entries for reclaim/DLQ. */
    private Duration reclaimInterval = Duration.ofSeconds(5);
    /**
     * Identity of this process inside the consumer group. Left unset it is derived per process, which
     * is what keeps two replicas of the same image from sharing a consumer name.
     */
    private String instanceId;
    /** How long a claimed reclaim turn stays owned. A crashed coordinator frees it after this. */
    private Duration reclaimLease = Duration.ofSeconds(10);
    /** First delay before a worker retries a lost or refused Redis connection. */
    private Duration connectBackoffMin = Duration.ofMillis(200);
    /** Ceiling for the reconnect backoff, and the age at which a connection counts as healthy. */
    private Duration connectBackoffMax = Duration.ofSeconds(5);
    /** Test hook: when set, the worker fails any job whose reference equals this (drives DLQ tests). */
    private String failOnReference;

    public Duration getWaitTimeout() {
        return waitTimeout;
    }

    public void setWaitTimeout(Duration waitTimeout) {
        this.waitTimeout = waitTimeout;
    }

    public Duration getResultTtl() {
        return resultTtl;
    }

    public void setResultTtl(Duration resultTtl) {
        this.resultTtl = resultTtl;
    }

    public Duration getStatusTtl() {
        return statusTtl;
    }

    public void setStatusTtl(Duration statusTtl) {
        this.statusTtl = statusTtl;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public boolean isIdempotencyRequired() {
        return idempotencyRequired;
    }

    public void setIdempotencyRequired(boolean idempotencyRequired) {
        this.idempotencyRequired = idempotencyRequired;
    }

    /**
     * Fails startup on a retention layout that cannot express its own states: a status that expires
     * before its result turns every finished job into "unknown" the moment the status lapses.
     */
    @PostConstruct
    void validateRetention() {
        if (statusTtl.compareTo(resultTtl) < 0) {
            throw new ConfigurationException(
                    "async.redis.status-ttl (" + statusTtl + ") must be >= result-ttl (" + resultTtl + ")");
        }
        if (poolMaxTotal <= 0) {
            throw new ConfigurationException(
                    "async.redis.pool-max-total must be greater than zero; an unbounded wait pool has"
                            + " no backpressure to apply");
        }
        if (poolMaxWait.isNegative() || poolMaxWait.isZero()) {
            throw new ConfigurationException(
                    "async.redis.pool-max-wait must be a positive, finite duration");
        }
        if (reclaimLease.compareTo(reclaimInterval) <= 0) {
            throw new ConfigurationException(
                    "async.redis.reclaim-lease (" + reclaimLease + ") must be greater than"
                            + " async.redis.reclaim-interval (" + reclaimInterval + "); a lease that"
                            + " expires within one scan lets a second worker reclaim concurrently");
        }
        if (connectBackoffMin.isNegative() || connectBackoffMin.isZero()) {
            throw new ConfigurationException(
                    "async.redis.connect-backoff-min must be a positive, finite duration; a worker"
                            + " that retries with no delay spins against an unreachable Redis");
        }
        if (connectBackoffMax.compareTo(connectBackoffMin) < 0) {
            throw new ConfigurationException(
                    "async.redis.connect-backoff-max (" + connectBackoffMax + ") must be >="
                            + " async.redis.connect-backoff-min (" + connectBackoffMin + ")");
        }
    }

    public int getWorkerConcurrency() {
        return workerConcurrency;
    }

    public void setWorkerConcurrency(int workerConcurrency) {
        this.workerConcurrency = workerConcurrency;
    }

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public long getProcessLatencyMinMs() {
        return processLatencyMinMs;
    }

    public void setProcessLatencyMinMs(long processLatencyMinMs) {
        this.processLatencyMinMs = processLatencyMinMs;
    }

    public long getProcessLatencyMaxMs() {
        return processLatencyMaxMs;
    }

    public void setProcessLatencyMaxMs(long processLatencyMaxMs) {
        this.processLatencyMaxMs = processLatencyMaxMs;
    }

    public Duration getReclaimIdle() {
        return reclaimIdle;
    }

    public void setReclaimIdle(Duration reclaimIdle) {
        this.reclaimIdle = reclaimIdle;
    }

    public long getStreamMaxlen() {
        return streamMaxlen;
    }

    public void setStreamMaxlen(long streamMaxlen) {
        this.streamMaxlen = streamMaxlen;
    }

    public String getDlqStream() {
        return dlqStream;
    }

    public void setDlqStream(String dlqStream) {
        this.dlqStream = dlqStream;
    }

    public int getMaxDeliveries() {
        return maxDeliveries;
    }

    public void setMaxDeliveries(int maxDeliveries) {
        this.maxDeliveries = maxDeliveries;
    }

    public int getAdmissionLimitPerSec() {
        return admissionLimitPerSec;
    }

    public void setAdmissionLimitPerSec(int admissionLimitPerSec) {
        this.admissionLimitPerSec = admissionLimitPerSec;
    }

    public int getPoolMaxTotal() {
        return poolMaxTotal;
    }

    public void setPoolMaxTotal(int poolMaxTotal) {
        this.poolMaxTotal = poolMaxTotal;
    }

    public Duration getPoolMaxWait() {
        return poolMaxWait;
    }

    public void setPoolMaxWait(Duration poolMaxWait) {
        this.poolMaxWait = poolMaxWait;
    }

    public Duration getReclaimInterval() {
        return reclaimInterval;
    }

    public void setReclaimInterval(Duration reclaimInterval) {
        this.reclaimInterval = reclaimInterval;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Duration getReclaimLease() {
        return reclaimLease;
    }

    public void setReclaimLease(Duration reclaimLease) {
        this.reclaimLease = reclaimLease;
    }

    public Duration getConnectBackoffMin() {
        return connectBackoffMin;
    }

    public void setConnectBackoffMin(Duration connectBackoffMin) {
        this.connectBackoffMin = connectBackoffMin;
    }

    public Duration getConnectBackoffMax() {
        return connectBackoffMax;
    }

    public void setConnectBackoffMax(Duration connectBackoffMax) {
        this.connectBackoffMax = connectBackoffMax;
    }

    public String getFailOnReference() {
        return failOnReference;
    }

    public void setFailOnReference(String failOnReference) {
        this.failOnReference = failOnReference;
    }
}
