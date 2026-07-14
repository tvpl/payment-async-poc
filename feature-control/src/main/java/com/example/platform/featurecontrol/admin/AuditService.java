package com.example.platform.featurecontrol.admin;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.store.FeatureRedisCommandsProvider;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;

/**
 * Records who changed which flag and how — the governance half of runtime flag control. Always logs a
 * structured line (SLF4J + MDC: {@code actor}, {@code flag}, {@code action}), and, when Redis is
 * present, also appends to a capped list {@code <key-prefix>audit} (LPUSH + LTRIM) so the recent
 * history is queryable without a log pipeline. Best-effort: an audit failure never blocks the change.
 */
@Singleton
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger("feature.audit");
    private static final long MAX_ENTRIES = 1000;

    private final String auditKey;
    @Nullable
    private final FeatureRedisCommandsProvider redis;

    public AuditService(FeatureSettings settings, @Nullable FeatureRedisCommandsProvider redis) {
        this.auditKey = settings.getKeyPrefix() + "audit";
        this.redis = redis;
    }

    public void record(String actor, String flag, String action, String detail) {
        String who = actor == null || actor.isBlank() ? "anonymous" : actor;
        MDC.put("actor", who);
        MDC.put("flag", flag);
        MDC.put("action", action);
        try {
            LOG.info("feature admin action={} flag={} actor={} detail={}", action, flag, who, detail);
        } finally {
            MDC.remove("actor");
            MDC.remove("flag");
            MDC.remove("action");
        }
        if (redis != null) {
            try {
                String line = Instant.now() + " " + action + " " + flag + " by " + who
                        + (detail == null ? "" : " (" + detail + ")");
                redis.commands().lpush(auditKey, line);
                redis.commands().ltrim(auditKey, 0, MAX_ENTRIES - 1);
            } catch (Exception e) {
                LOG.debug("audit persist skipped: {}", e.getMessage());
            }
        }
    }
}
