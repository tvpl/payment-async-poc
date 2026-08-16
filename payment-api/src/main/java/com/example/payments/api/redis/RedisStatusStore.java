package com.example.payments.api.redis;

import com.example.payments.api.config.ApiProperties;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.error.StoreUnavailableException;
import com.example.payments.api.idempotency.IdempotencyOutcome;
import com.example.payments.api.idempotency.IdempotencyReservation;
import com.example.payments.api.idempotency.PublishState;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.serde.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Redis-backed shared state for the API: request status/result and the
 * idempotencyKey -&gt; requestId mapping, so correlation and de-duplication work
 * across instances.
 *
 * <p>The connection is obtained <strong>lazily</strong> from {@link RedisClient} (and
 * re-established if dropped), so the application boots even when Redis is briefly
 * unavailable instead of crashing at startup.
 *
 * <p>Every public method fails closed: a {@link RedisException} (connection lost, command
 * timeout, or a fresh connect attempt failing outright) is translated to
 * {@link StoreUnavailableException} rather than escaping as a raw driver exception. Idempotency
 * has no local substitute — without Redis there is no way to guarantee a request is not
 * duplicated — so a store outage must reject the caller, not silently degrade.
 */
@Singleton
public class RedisStatusStore {

    private static final Logger LOG = LoggerFactory.getLogger(RedisStatusStore.class);
    private static final String STATUS_PREFIX = "payment-simulation:";
    private static final String IDEM_PREFIX = "idem:";

    private final RedisClient redisClient;
    private final ObjectMapper objectMapper;
    private final ApiProperties properties;

    private volatile StatefulRedisConnection<String, String> connection;

    public RedisStatusStore(RedisClient redisClient,
                            ObjectMapper objectMapper,
                            ApiProperties properties) {
        this.redisClient = redisClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    private RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> conn = connection;
        if (conn == null || !conn.isOpen()) {
            synchronized (this) {
                if (connection == null || !connection.isOpen()) {
                    connection = redisClient.connect();
                }
            }
        }
        return connection.sync();
    }

    public void save(StatusEntry entry) {
        String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save status for " + entry.requestId(), e);
        }
        try {
            commands().set(statusKey(entry.requestId()), json,
                    SetArgs.Builder.px(properties.getStatusTtl().toMillis()));
        } catch (RedisException e) {
            throw new StoreUnavailableException("Failed to save status for " + entry.requestId(), e);
        }
    }

    public Optional<StatusEntry> get(String requestId) {
        String json;
        try {
            json = commands().get(statusKey(requestId));
        } catch (RedisException e) {
            throw new StoreUnavailableException("Failed to read status for " + requestId, e);
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, StatusEntry.class));
        } catch (Exception e) {
            LOG.error("Failed to read status entry for {}", requestId, e);
            return Optional.empty();
        }
    }

    /**
     * Atomically reserves an idempotency key for this requestId + payload fingerprint,
     * scoped to {@code tenantId} (PAY-01, TEN-04, TEN-05). Identity, fingerprint and publish
     * state are stored together in a single {@code SET NX} so no request can observe a key
     * associated with only half the identity. The Redis key includes the tenant
     * ({@code idem:{tenant}:{key}}), so two tenants reusing the same {@code idempotencyKey} and
     * payload never see each other's reservation, replay, or conflict (TEN-04).
     *
     * <p>Same key + same fingerprint replays the original identity (PAY-02); same key with
     * a different fingerprint is a deterministic conflict, never a silent replay.
     *
     * <p>A matching key whose publish was never confirmed is returned as
     * {@link IdempotencyOutcome.ResumePublish} once its lease has lapsed, so a crashed or
     * failed attempt is recovered under the same requestId instead of leaving a reservation
     * that simulates processing until it expires (PAY-03).
     */
    public IdempotencyOutcome reserve(String tenantId, String idempotencyKey, String requestId, String fingerprint) {
        String key = idemKey(tenantId, idempotencyKey);
        String value = writeReservation(new IdempotencyReservation(
                requestId, fingerprint, PublishState.PENDING_PUBLISH, leaseDeadline()));
        long ttlMillis = properties.getIdempotencyTtl().toMillis();
        try {
            // Two attempts: a SET NX can lose the race to a reservation that expires between the
            // failed NX and the follow-up GET; retrying once claims the now-free key
            // deterministically instead of surfacing a spurious failure for an astronomically
            // narrow window.
            for (int attempt = 0; attempt < 2; attempt++) {
                String result = commands().set(key, value, SetArgs.Builder.nx().px(ttlMillis));
                if ("OK".equals(result)) {
                    return new IdempotencyOutcome.Reserved();
                }
                String existingValue = commands().get(key);
                if (existingValue == null) {
                    continue;
                }
                IdempotencyReservation existing = readReservation(existingValue);
                if (!fingerprint.equals(existing.fingerprint())) {
                    return new IdempotencyOutcome.Conflict(existing.requestId());
                }
                return isResumable(existing)
                        ? new IdempotencyOutcome.ResumePublish(existing.requestId())
                        : new IdempotencyOutcome.Replay(existing.requestId());
            }
        } catch (RedisException e) {
            throw new StoreUnavailableException("Failed to reserve idempotency key: " + idempotencyKey, e);
        }
        throw new IllegalStateException("Failed to reserve idempotency key: " + idempotencyKey);
    }

    /**
     * Records the outcome of the Kafka publish on an existing reservation, keeping the
     * original expiry ({@code KEEPTTL}) so recovery never extends the dedup window.
     *
     * <p>{@code XX} means an already-expired reservation is not resurrected: there is no
     * identity left to recover.
     */
    public void markPublishState(String tenantId,
                                 String idempotencyKey,
                                 String requestId,
                                 String fingerprint,
                                 PublishState publishState) {
        String value = writeReservation(
                new IdempotencyReservation(requestId, fingerprint, publishState, leaseDeadline()));
        try {
            commands().set(idemKey(tenantId, idempotencyKey), value, SetArgs.Builder.xx().keepttl());
        } catch (RedisException e) {
            throw new StoreUnavailableException("Failed to mark publish state for " + requestId, e);
        }
    }

    /**
     * An unconfirmed publish is recoverable once nobody is working on it: either the owner
     * reported the failure, or its lease lapsed (the process died mid-attempt). While the
     * lease holds, a concurrent duplicate replays instead of publishing the identity twice.
     */
    private boolean isResumable(IdempotencyReservation reservation) {
        if (reservation.publishState() == PublishState.PUBLISHED) {
            return false;
        }
        return reservation.publishState() == PublishState.PUBLISH_FAILED
                || reservation.publishLeaseExpiresAt() <= System.currentTimeMillis();
    }

    private long leaseDeadline() {
        return System.currentTimeMillis() + properties.getPublishLease().toMillis();
    }

    private String writeReservation(IdempotencyReservation reservation) {
        try {
            return objectMapper.writeValueAsString(reservation);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode idempotency reservation", e);
        }
    }

    private IdempotencyReservation readReservation(String json) {
        try {
            return objectMapper.readValue(json, IdempotencyReservation.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode idempotency reservation", e);
        }
    }

    public void publishResponse(String requestId) {
        try {
            commands().publish(properties.getResponseChannel(), requestId);
        } catch (RedisException e) {
            throw new StoreUnavailableException("Failed to publish response for " + requestId, e);
        }
    }

    @PreDestroy
    void close() {
        if (connection != null) {
            connection.close();
        }
    }

    private static String statusKey(String requestId) {
        return STATUS_PREFIX + requestId;
    }

    private static String idemKey(String tenantId, String idempotencyKey) {
        return IDEM_PREFIX + tenantId + ":" + idempotencyKey;
    }
}
