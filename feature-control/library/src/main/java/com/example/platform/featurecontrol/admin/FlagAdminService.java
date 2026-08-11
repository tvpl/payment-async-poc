package com.example.platform.featurecontrol.admin;

import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.store.FlagChangeNotifier;
import com.example.platform.featurecontrol.store.RedisFlagSource;
import com.example.platform.featurecontrol.store.VersionedFlagStore;
import io.lettuce.core.RedisClient;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/**
 * Write path for runtime flag control, with <strong>optimistic concurrency on both create/update and
 * delete</strong> (FTR-04). Every mutation goes through {@link VersionedFlagStore}, whose Lua script
 * performs the compare-and-set and writes the audit entry (before/after/actor/version/timestamp/result)
 * to a Redis Stream in the same atomic step — so a mutation without a matching audit entry cannot
 * happen. The loser of a concurrent write gets a {@link FlagConflictException} (HTTP 409) and re-reads.
 *
 * <p>Every mutation requires a non-blank {@code actor} — an unauthenticated/anonymous caller cannot
 * mutate a flag through this service, regardless of what HTTP-layer authorization a consuming app
 * configures on top of it.
 *
 * <p>On success the stored copy carries the new version, the local cache is invalidated, and a change
 * signal is published so every instance drops its cache within milliseconds (see
 * {@link FlagChangeNotifier}). Only wired when Redis is available.
 */
@Singleton
@Requires(beans = RedisClient.class)
public class FlagAdminService {

    private final VersionedFlagStore store;
    private final ObjectMapper objectMapper;
    private final FlagChangeNotifier notifier;
    @Nullable
    private final RedisFlagSource dynamicSource;

    public FlagAdminService(VersionedFlagStore store,
                            ObjectMapper objectMapper,
                            FlagChangeNotifier notifier,
                            @Nullable RedisFlagSource dynamicSource) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.notifier = notifier;
        this.dynamicSource = dynamicSource;
    }

    /**
     * Upserts a flag using compare-and-set on {@code definition.version()}. Send version 0 to create;
     * to update, send the version you last read. On success the returned definition carries the new
     * version.
     *
     * @throws FlagConflictException   if another writer bumped the version first (HTTP 409).
     * @throws IllegalArgumentException if {@code actor} is null or blank.
     */
    public FlagDefinition put(FlagDefinition definition, String actor) {
        requireActor(actor);
        long expected = definition.version();
        FlagDefinition next = definition.withVersion(expected + 1);
        try {
            String json = objectMapper.writeValueAsString(next);
            long result = store.put(definition.name(), expected, json, actor);
            if (result == -1L) {
                throw new FlagConflictException(definition.name(), expected, store.currentVersion(definition.name()));
            }
        } catch (FlagConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist flag " + definition.name(), e);
        }
        invalidate(definition.name());
        notifier.publish(definition.name());
        return next;
    }

    /**
     * Removes the dynamic override (so the YAML baseline applies again) using compare-and-set on
     * {@code expectedVersion} — the version the caller last read, or {@code 0} for a flag it never
     * saw created.
     *
     * @throws FlagConflictException    if the stored version no longer matches {@code expectedVersion}
     *                                   (HTTP 409) — someone else changed or deleted it first.
     * @throws IllegalArgumentException if {@code actor} is null or blank.
     */
    public void delete(String name, long expectedVersion, String actor) {
        requireActor(actor);
        long result = store.delete(name, expectedVersion, actor);
        if (result == -1L) {
            throw new FlagConflictException(name, expectedVersion, store.currentVersion(name));
        }
        invalidate(name);
        notifier.publish(name);
    }

    /**
     * The version currently stored in Redis, read straight from the source of truth — never from the
     * cached {@link com.example.platform.featurecontrol.spi.FlagSource} resolver, whose LKG cache (T48)
     * can lag the store by up to {@code cache-ttl} and would hand a caller a stale CAS baseline for
     * {@link #delete}. {@code 0} means absent.
     */
    public long currentVersion(String name) {
        return store.currentVersion(name);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("admin mutation requires a non-blank authenticated actor");
        }
    }

    private void invalidate(String name) {
        if (dynamicSource != null) {
            dynamicSource.invalidate(name);
        }
    }
}
