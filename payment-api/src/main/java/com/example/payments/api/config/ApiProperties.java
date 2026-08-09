package com.example.payments.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/** API tunables: how long to wait synchronously, Redis TTLs, pub/sub channel. */
@ConfigurationProperties("payment.simulation")
public class ApiProperties {

    /** Max time the HTTP request blocks (on a virtual thread) for the async result. */
    private Duration waitTimeout = Duration.ofSeconds(3);
    /** TTL of the per-request status/result entry in Redis. */
    private Duration statusTtl = Duration.ofMinutes(15);
    /** TTL of the idempotencyKey -> requestId mapping in Redis. */
    private Duration idempotencyTtl = Duration.ofMinutes(15);
    /** Redis pub/sub channel used to wake waiters across instances. */
    private String responseChannel = "payment-sim-responses";

    public Duration getWaitTimeout() {
        return waitTimeout;
    }

    public void setWaitTimeout(Duration waitTimeout) {
        this.waitTimeout = waitTimeout;
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

    public String getResponseChannel() {
        return responseChannel;
    }

    public void setResponseChannel(String responseChannel) {
        this.responseChannel = responseChannel;
    }
}
