package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import jakarta.inject.Singleton;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Names this process's consumers inside the shared consumer group (RED-04).
 *
 * <p>A Redis consumer group keys its Pending Entries List by consumer <em>name</em>. Two processes
 * that pick the same name are, as far as Redis is concerned, one consumer: each sees the other's
 * pending entries as its own, {@code XREADGROUP} hands the same backlog to both, and a restart of
 * one silently adopts the in-flight work of the other. The fixed {@code worker-N} naming this
 * replaces did exactly that for every replica of the same image.
 *
 * <p>The identity is therefore per <em>process</em> and per <em>worker</em>. A hostname alone is not
 * enough: containers of one deployment share it, and two instances can run on one host. The random
 * suffix is what makes it unique; the hostname is kept only so an operator reading {@code XINFO
 * CONSUMERS} can tell where a consumer lives.
 */
@Singleton
public class WorkerIdentity {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String instanceId;

    public WorkerIdentity(AsyncRedisProperties props) {
        String configured = props.getInstanceId();
        this.instanceId = configured == null || configured.isBlank() ? derive() : configured.trim();
    }

    /** Stable for the life of this process. */
    public String instanceId() {
        return instanceId;
    }

    /**
     * The consumer name for one worker thread. Stable across calls: a name regenerated per poll would
     * strand every previously delivered entry under a consumer that no longer exists.
     */
    public String consumerName(int workerIndex) {
        return instanceId + "-w" + workerIndex;
    }

    private static String derive() {
        return host() + "-" + String.format("%08x", RANDOM.nextInt());
    }

    private static String host() {
        try {
            String name = InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
            String sanitized = name.replaceAll("[^a-z0-9]+", "-");
            return sanitized.isBlank() ? "instance" : sanitized;
        } catch (Exception e) {
            return "instance";
        }
    }
}
