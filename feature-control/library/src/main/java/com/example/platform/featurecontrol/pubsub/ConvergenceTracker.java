package com.example.platform.featurecontrol.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * FTR-03: "WHEN uma flag for alterada ou removida THEN all instances SHALL convergir dentro do
 * limite aprovado ou emitir alerta de degradação." Records how long it took this instance to observe
 * a change after it was published (embedded in the pub/sub payload — see {@code FlagChangeNotifier}),
 * and logs a degradation alert whenever that exceeds the approved limit.
 */
public class ConvergenceTracker {

    private static final Logger LOG = LoggerFactory.getLogger("feature.convergence");

    private final Duration approvedLimit;
    private volatile Duration lastLatency;
    private volatile boolean lastDegraded;

    public ConvergenceTracker(Duration approvedLimit) {
        this.approvedLimit = approvedLimit;
    }

    /** Records one observed convergence latency (publish -> this instance noticing the change). */
    public void record(String flagName, Duration latency) {
        this.lastLatency = latency;
        this.lastDegraded = latency.compareTo(approvedLimit) > 0;
        if (lastDegraded) {
            LOG.warn("feature-flag convergence degraded: flag={} latency={} approvedLimit={}",
                    flagName, latency, approvedLimit);
        }
    }

    /** The most recently observed convergence latency, if any change has been observed yet. */
    public Optional<Duration> lastLatency() {
        return Optional.ofNullable(lastLatency);
    }

    /** Whether the most recently observed convergence exceeded the approved limit. */
    public boolean isLastDegraded() {
        return lastDegraded;
    }

    public Duration approvedLimit() {
        return approvedLimit;
    }
}
