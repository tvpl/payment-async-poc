package com.example.payments.coremock;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.annotation.PostConstruct;

/**
 * Tunable behavior of the simulated Core. Lets demos exercise different regimes without
 * rebuilding: raise the latency to force {@code 202}/timeouts, raise the decline rate to
 * see {@code 422}s, or raise the failure rate to drive transient errors into the retry
 * topics and DLQ. Overridable via env (e.g. {@code CORE_LATENCY_MAX_MS}, {@code CORE_FAIL_PCT}).
 */
@ConfigurationProperties("core.behavior")
public class CoreBehaviorProperties {

    /** Minimum simulated processing latency, in milliseconds. */
    private int latencyMinMs = 50;
    /** Maximum simulated processing latency, in milliseconds. */
    private int latencyMaxMs = 300;
    /** Percent of commands answered as DECLINED (0-100). */
    private int declinePct = 10;
    /** Percent of commands that throw a transient error (0-100) → retry topic / DLQ. */
    private int failPct = 0;
    /** Stable seed used with requestId to make every simulated decision reproducible. */
    private long seed = 20260808L;

    public int getLatencyMinMs() {
        return latencyMinMs;
    }

    public void setLatencyMinMs(int latencyMinMs) {
        this.latencyMinMs = latencyMinMs;
    }

    public int getLatencyMaxMs() {
        return latencyMaxMs;
    }

    public void setLatencyMaxMs(int latencyMaxMs) {
        this.latencyMaxMs = latencyMaxMs;
    }

    public int getDeclinePct() {
        return declinePct;
    }

    public void setDeclinePct(int declinePct) {
        this.declinePct = declinePct;
    }

    public int getFailPct() {
        return failPct;
    }

    public void setFailPct(int failPct) {
        this.failPct = failPct;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    @PostConstruct
    public void validate() {
        snapshot();
    }

    public Behavior snapshot() {
        return new Behavior(latencyMinMs, latencyMaxMs, declinePct, failPct, seed);
    }

    public record Behavior(
            int latencyMinMs,
            int latencyMaxMs,
            int declinePct,
            int failPct,
            long seed) {

        public Behavior {
            if (latencyMinMs < 0 || latencyMaxMs < 0) {
                throw new IllegalStateException("Core latency bounds must be non-negative");
            }
            if (latencyMinMs > latencyMaxMs) {
                throw new IllegalStateException("Core minimum latency must not exceed maximum latency");
            }
            if (declinePct < 0 || declinePct > 100) {
                throw new IllegalStateException("Core decline percentage must be between 0 and 100");
            }
            if (failPct < 0 || failPct > 100) {
                throw new IllegalStateException("Core failure percentage must be between 0 and 100");
            }
            if (declinePct + failPct > 100) {
                throw new IllegalStateException("Core decline and failure percentages must not exceed 100");
            }
        }
    }
}
