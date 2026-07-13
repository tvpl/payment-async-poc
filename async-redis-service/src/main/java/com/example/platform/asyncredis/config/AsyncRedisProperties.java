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
}
