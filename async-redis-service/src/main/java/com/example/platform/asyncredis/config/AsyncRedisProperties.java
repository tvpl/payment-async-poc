package com.example.platform.asyncredis.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/** Tunables for the Kafka-free async->sync flow. Bound from {@code async.redis.*}. */
@ConfigurationProperties("async.redis")
public class AsyncRedisProperties {

    private Duration waitTimeout = Duration.ofSeconds(3);
    private Duration resultTtl = Duration.ofMinutes(15);
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
    /** How often a worker scans pending entries for reclaim/DLQ. */
    private Duration reclaimInterval = Duration.ofSeconds(5);
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

    public Duration getReclaimInterval() {
        return reclaimInterval;
    }

    public void setReclaimInterval(Duration reclaimInterval) {
        this.reclaimInterval = reclaimInterval;
    }

    public String getFailOnReference() {
        return failOnReference;
    }

    public void setFailOnReference(String failOnReference) {
        this.failOnReference = failOnReference;
    }
}
