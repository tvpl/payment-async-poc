package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import io.lettuce.core.RedisClient;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a flag-changed signal on a Redis pub/sub channel so every instance of every app can drop
 * its cached copy immediately, instead of waiting up to {@code cache-ttl}. Called by
 * {@link com.example.platform.featurecontrol.admin.FlagAdminService} after a write. Best-effort: if
 * the publish fails, the {@code cache-ttl} still guarantees eventual convergence.
 */
@Singleton
@Requires(beans = RedisClient.class)
public class FlagChangeNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(FlagChangeNotifier.class);

    private final FeatureRedisCommandsProvider redis;
    private final String channel;

    public FlagChangeNotifier(FeatureRedisCommandsProvider redis, FeatureSettings settings) {
        this.redis = redis;
        this.channel = settings.getKeyPrefix() + "changed";
    }

    public String channel() {
        return channel;
    }

    /** Announce that {@code flagName} changed (or {@code *} to invalidate everything). */
    public void publish(String flagName) {
        try {
            redis.commands().publish(channel, flagName);
        } catch (Exception e) {
            LOG.debug("flag-change publish failed for {} ({}); cache-ttl will converge", flagName, e.getMessage());
        }
    }
}
