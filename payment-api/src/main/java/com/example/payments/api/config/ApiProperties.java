package com.example.payments.api.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;

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
    /** How long a publish attempt is considered in flight before a retry may resume it. */
    private Duration publishLease = Duration.ofSeconds(30);

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

    public Duration getPublishLease() {
        return publishLease;
    }

    public void setPublishLease(Duration publishLease) {
        this.publishLease = publishLease;
    }

    /**
     * The idempotency reservation must outlive the status entry it guards (PAY-11): otherwise
     * the dedup key could expire while the original request is still visible/in-flight,
     * letting a retried submission slip through as if it were new.
     */
    @PostConstruct
    public void validate() {
        if (waitTimeout == null || waitTimeout.isNegative() || waitTimeout.isZero()) {
            throw new ConfigurationException("payment.simulation.wait-timeout must be positive");
        }
        if (statusTtl == null || statusTtl.isNegative() || statusTtl.isZero()) {
            throw new ConfigurationException("payment.simulation.status-ttl must be positive");
        }
        if (idempotencyTtl == null || idempotencyTtl.isNegative() || idempotencyTtl.isZero()) {
            throw new ConfigurationException("payment.simulation.idempotency-ttl must be positive");
        }
        if (publishLease == null || publishLease.isNegative() || publishLease.isZero()) {
            throw new ConfigurationException("payment.simulation.publish-lease must be positive");
        }
        if (idempotencyTtl.compareTo(statusTtl) < 0) {
            throw new ConfigurationException(
                    "payment.simulation.idempotency-ttl must be >= status-ttl, "
                            + "otherwise a duplicate can slip through after the reservation expires "
                            + "while the original status is still visible");
        }
    }
}
