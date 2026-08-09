package com.example.platform.asyncredis.worker;

import jakarta.inject.Singleton;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which workers currently have proven consuming capacity (RED-05).
 *
 * <p>A worker counts only once it has actually read from the group on a live connection. Being
 * constructed, or holding a socket, is not capacity: a process whose Redis is unreachable can still
 * accept HTTP traffic and enqueue jobs nothing will ever consume. Readiness has to reflect the
 * consuming side, so traffic is not routed to an instance that cannot do the work.
 */
@Singleton
public class WorkerReadiness {

    private final Set<String> consuming = ConcurrentHashMap.newKeySet();

    /** Records that {@code consumerName} just read from the group successfully. Idempotent. */
    public void markConsuming(String consumerName) {
        consuming.add(consumerName);
    }

    /** Records that {@code consumerName} lost its connection or stopped. Idempotent. */
    public void markUnavailable(String consumerName) {
        consuming.remove(consumerName);
    }

    /** True once at least one worker can consume. */
    public boolean hasConsumingCapacity() {
        return !consuming.isEmpty();
    }

    public int consumingWorkers() {
        return consuming.size();
    }
}
