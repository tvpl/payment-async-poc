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
    /** TTL of the idempotencyKey -> requestId mapping in Redis (IDEM-03: 24h per the published contract). */
    private Duration idempotencyTtl = Duration.ofHours(24);
    /** Redis pub/sub channel used to wake waiters across instances. */
    private String responseChannel = "payment-sim-responses";
    /** SCAL-05: number of hash-sharded channels the response channel is split into. */
    private int responseChannelShards = 4;
    /**
     * SCAL-05: while true, {@code publishResponse} also publishes on the legacy, unsharded
     * channel so instances not yet upgraded to shard-aware subscription still wake up. Kept for
     * one release, then flipped off once the fleet has fully transitioned.
     */
    private boolean responseChannelLegacyEnabled = true;
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

    public int getResponseChannelShards() {
        return responseChannelShards;
    }

    public void setResponseChannelShards(int responseChannelShards) {
        this.responseChannelShards = responseChannelShards;
    }

    public boolean isResponseChannelLegacyEnabled() {
        return responseChannelLegacyEnabled;
    }

    public void setResponseChannelLegacyEnabled(boolean responseChannelLegacyEnabled) {
        this.responseChannelLegacyEnabled = responseChannelLegacyEnabled;
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
        if (responseChannelShards < 1) {
            throw new ConfigurationException("payment.simulation.response-channel-shards must be >= 1");
        }
        if (idempotencyTtl.compareTo(statusTtl) < 0) {
            throw new ConfigurationException(
                    "payment.simulation.idempotency-ttl must be >= status-ttl, "
                            + "otherwise a duplicate can slip through after the reservation expires "
                            + "while the original status is still visible");
        }
    }
}
