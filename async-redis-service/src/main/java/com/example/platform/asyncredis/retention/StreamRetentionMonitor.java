package com.example.platform.asyncredis.retention;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports whether the job stream's pending payload is safe from retention pressure (RED-03).
 *
 * <p>The stream used to be trimmed inline on every {@code XADD} with {@code MAXLEN ~}
 * (approximate). That trims by raw entry count, with no notion of a consumer group's Pending
 * Entries List — it can and does delete entries a worker has not consumed yet whenever the backlog
 * outgrows the configured length. {@code JobQueue.enqueue} no longer does this at all; this class is
 * what replaces it, and it never trims:
 *
 * <ul>
 *   <li>Redis 8.2+ ships an {@code ACKED} trim strategy that only removes entries every consumer
 *       group has acknowledged — the PEL-safe behavior this service needs. No Redis client version
 *       pinned in this build (Lettuce 6.4.0.RELEASE — {@code XTrimArgs} has no {@code ACKED} option)
 *       has typed support for it yet, so this service does not attempt it. {@link
 *       #ackedTrimSupported} reports whether the connected server is new enough, purely as an
 *       operational signal for when a future Lettuce upgrade can turn automatic trimming on.
 *   <li>On any Redis version — compatible or not — the safe fallback per the local ADR is no
 *       automatic trim at all. An operator trims manually (with the PEL checked) once memory
 *       pressure actually requires it. This is a deliberate, permanent property of this class, not
 *       a temporary gap: it is what "an incompatible version fails safe" means for RED-03.
 * </ul>
 *
 * <p>What this class does instead is alert before the backlog becomes an operational problem: {@link
 * #check} compares the stream's current length against {@code stream-maxlen *
 * retention-alert-threshold} and logs a warning once it is reached, on a schedule
 * (retention-check-interval).
 */
@Singleton
public class StreamRetentionMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(StreamRetentionMonitor.class);

    /** Minimum Redis server version (inclusive) that ships the PEL-aware {@code ACKED} trim strategy. */
    static final String MIN_ACKED_TRIM_VERSION = "8.2.0";

    private final RedisConnections redis;
    private final AsyncRedisProperties props;

    public StreamRetentionMonitor(RedisConnections redis, AsyncRedisProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Scheduled(fixedDelay = "${async.redis.retention-check-interval:30s}")
    void scheduledCheck() {
        check();
    }

    /**
     * Reads the current backlog and the server's trim capability. Never trims anything — see the
     * class Javadoc. Logs a warning when the backlog has reached the configured safe budget.
     */
    public RetentionStatus check() {
        String version = serverVersion();
        boolean ackedSupported = version != null && compareVersions(version, MIN_ACKED_TRIM_VERSION) >= 0;
        long length = streamLength();
        long threshold = Math.round(props.getStreamMaxlen() * props.getRetentionAlertThreshold());
        boolean alert = length >= threshold;
        if (alert) {
            LOG.warn("stream {} backlog is {} entries, at or above the safe budget of {} (max {}); "
                            + "auto-trim stays off so no pending payload is at risk, but capacity should"
                            + " be reviewed",
                    props.getStream(), length, threshold, props.getStreamMaxlen());
        }
        return new RetentionStatus(version, ackedSupported, length, threshold, alert);
    }

    /** The connected Redis's {@code redis_version} from {@code INFO server}, or {@code null}. */
    String serverVersion() {
        try {
            String info = redis.shared().info("server");
            if (info == null) {
                return null;
            }
            for (String line : info.split("\r\n")) {
                if (line.startsWith("redis_version:")) {
                    return line.substring("redis_version:".length()).trim();
                }
            }
            return null;
        } catch (Exception e) {
            LOG.debug("could not read the Redis server version: {}", e.getMessage());
            return null;
        }
    }

    private long streamLength() {
        try {
            Long len = redis.shared().xlen(props.getStream());
            return len == null ? 0 : len;
        } catch (Exception e) {
            LOG.debug("could not read stream length: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Numeric, dotted-segment version compare (e.g. {@code "7.0.15"} vs {@code "8.2.0"}). A
     * non-numeric segment (a pre-release suffix, say) counts as {@code 0} rather than failing the
     * comparison outright, so an unusual version string degrades to "not newer" instead of throwing.
     */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("[.\\-]");
        String[] partsB = b.split("[.\\-]");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            int diff = Integer.compare(part(partsA, i), part(partsB, i));
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
