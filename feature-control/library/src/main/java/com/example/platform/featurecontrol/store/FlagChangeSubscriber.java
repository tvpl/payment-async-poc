package com.example.platform.featurecontrol.store;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Subscribes to the flag-changed channel and drops the matching {@link RedisFlagSource} cache entry so
 * a runtime flip is visible almost immediately across all instances (the {@code cache-ttl} becomes a
 * safety net, not the propagation delay). Mirrors the tolerant subscribe/retry lifecycle of the API's
 * {@code ResponseCoordinator}. Only active when Redis and the dynamic source are present.
 */
@Singleton
@Requires(beans = RedisClient.class)
@Requires(property = "platform.features.redis-enabled", notEquals = "false", defaultValue = "true")
public class FlagChangeSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(FlagChangeSubscriber.class);

    private final RedisClient redisClient;
    private final RedisFlagSource dynamicSource;
    private final String channel;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "feature-change-subscribe-retry");
                t.setDaemon(true);
                return t;
            });

    private volatile StatefulRedisPubSubConnection<String, String> pubSub;
    private volatile boolean shuttingDown;

    public FlagChangeSubscriber(RedisClient redisClient, RedisFlagSource dynamicSource,
                                FlagChangeNotifier notifier) {
        this.redisClient = redisClient;
        this.dynamicSource = dynamicSource;
        this.channel = notifier.channel();
    }

    @PostConstruct
    void start() {
        trySubscribe();
    }

    private void trySubscribe() {
        if (shuttingDown) {
            return;
        }
        try {
            pubSub = redisClient.connectPubSub();
            pubSub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String flagName) {
                    if ("*".equals(flagName)) {
                        dynamicSource.invalidateAll();
                    } else {
                        dynamicSource.invalidate(flagName);
                    }
                }
            });
            pubSub.sync().subscribe(channel);
            LOG.info("Subscribed to feature-change channel {}", channel);
        } catch (Exception e) {
            LOG.warn("feature-change subscribe failed; retrying in 5s ({})", e.getMessage());
            scheduler.schedule(this::trySubscribe, 5, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void close() {
        shuttingDown = true;
        scheduler.shutdownNow();
        if (pubSub != null) {
            pubSub.close();
        }
    }
}
