package com.example.platform.asyncredis.worker;

import com.example.platform.asyncredis.api.JobKeys;
import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Elects the single worker allowed to scan pending entries and reclaim them (RED-04).
 *
 * <p>Reclaim is not safe to run everywhere at once. Every worker scanning the same PEL races to
 * {@code XCLAIM} the same ids, so one entry gets processed by several workers and its delivery count
 * climbs on redeliveries nobody asked for, which in turn pushes healthy jobs toward the DLQ. One
 * owner at a time removes the race without a coordinator process.
 *
 * <p>The lease is the same shape as the outbox claim in {@code payment-sbus} (T28): an owner token
 * plus an expiry, released only by the owner. The storage engine differs (a Redis key rather than a
 * {@code FOR UPDATE SKIP LOCKED} row) but the invariants are the ones that matter: no double claim,
 * a stale owner is fenced out, and a crashed owner's turn frees itself once the lease expires
 * instead of stalling reclaim forever.
 */
@Singleton
public class ReclaimCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(ReclaimCoordinator.class);

    /** Extend only while still the owner. A lapsed owner must not resurrect its turn. */
    private static final String RENEW_IF_OWNER =
            "if redis.call('get', KEYS[1]) == ARGV[1] then"
                    + " return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";

    /** Release only what we own, so a slow owner cannot delete its successor's lease. */
    private static final String RELEASE_IF_OWNER =
            "if redis.call('get', KEYS[1]) == ARGV[1] then"
                    + " return redis.call('del', KEYS[1]) else return 0 end";

    private final RedisConnections redis;
    private final AsyncRedisProperties props;

    public ReclaimCoordinator(RedisConnections redis, AsyncRedisProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * Takes or extends this owner's reclaim turn.
     *
     * @return {@code true} when {@code owner} may reclaim right now; {@code false} when another
     *         worker holds the turn, or Redis could not answer (in which case not reclaiming is the
     *         safe choice).
     */
    public boolean claimTurn(String owner) {
        String key = JobKeys.reclaimLease(props.getGroup());
        long leaseMs = props.getReclaimLease().toMillis();
        try {
            String acquired = redis.shared().set(key, owner, SetArgs.Builder.nx().px(leaseMs));
            if ("OK".equals(acquired)) {
                return true;
            }
            // Already held: ours to extend, or someone else's to leave alone.
            Long renewed = redis.shared().eval(RENEW_IF_OWNER, ScriptOutputType.INTEGER,
                    new String[]{key}, owner, Long.toString(leaseMs));
            return renewed != null && renewed == 1L;
        } catch (Exception e) {
            LOG.debug("reclaim turn unavailable for {}: {}", owner, e.getMessage());
            return false;
        }
    }

    /** Gives the turn up early. A no-op when the lease has already moved on. */
    public void releaseTurn(String owner) {
        try {
            redis.shared().eval(RELEASE_IF_OWNER, ScriptOutputType.INTEGER,
                    new String[]{JobKeys.reclaimLease(props.getGroup())}, owner);
        } catch (Exception e) {
            LOG.debug("reclaim turn release skipped for {}: {}", owner, e.getMessage());
        }
    }

    /** The worker currently holding the turn, or {@code null} when nobody does. */
    public String currentOwner() {
        try {
            return redis.shared().get(JobKeys.reclaimLease(props.getGroup()));
        } catch (Exception e) {
            return null;
        }
    }
}
