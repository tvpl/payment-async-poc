package com.example.platform.featurecontrol.store;

import com.example.platform.featurecontrol.config.FeatureSettings;
import com.example.platform.featurecontrol.pubsub.ChangeMessage;
import com.example.platform.featurecontrol.pubsub.ConvergenceTracker;
import com.example.platform.featurecontrol.pubsub.LettucePubSubConnector;
import com.example.platform.featurecontrol.pubsub.PubSubConnector;
import com.example.platform.featurecontrol.pubsub.ReconnectBackoff;
import io.lettuce.core.RedisClient;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subscribes to the flag-changed channel and drops the matching {@link RedisFlagSource} cache entry so
 * a runtime flip is visible almost immediately across all instances (the {@code cache-ttl} becomes a
 * safety net, not the propagation delay). Only active when Redis and the dynamic source are present.
 *
 * <p><strong>No connection leak on restart/outage (FTR-03):</strong> the connect/add-listener/subscribe
 * sequence uses a local candidate connection; if any step after {@code connect()} fails, that
 * connection is closed before scheduling a retry, instead of being silently orphaned by the next
 * attempt overwriting the field.
 *
 * <p><strong>Reconnect with jitter (FTR-03):</strong> retries use {@link ReconnectBackoff} (capped
 * exponential + jitter) instead of a fixed delay, so many instances recovering from the same Redis
 * outage don't all reconnect in lockstep.
 *
 * <p><strong>Convergence measurement (FTR-03):</strong> each message's publish timestamp (embedded by
 * {@link FlagChangeNotifier}) is compared against the receive time and recorded in a
 * {@link ConvergenceTracker}, which alerts (logs) when the approved limit is exceeded.
 */
@Singleton
@Requires(beans = RedisClient.class)
@Requires(property = "platform.features.redis-enabled", notEquals = "false", defaultValue = "true")
public class FlagChangeSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(FlagChangeSubscriber.class);

    private final PubSubConnector connector;
    private final RedisFlagSource dynamicSource;
    private final String channel;
    private final ConvergenceTracker convergenceTracker;
    private final Duration reconnectBaseDelay;
    private final Duration reconnectMaxDelay;
    private final Random reconnectRandom = new Random();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "feature-change-subscribe-retry");
                t.setDaemon(true);
                return t;
            });

    private volatile PubSubConnector.Connection connection;
    private volatile boolean shuttingDown;

    public FlagChangeSubscriber(RedisClient redisClient, RedisFlagSource dynamicSource,
                                FlagChangeNotifier notifier, FeatureSettings settings) {
        this(new LettucePubSubConnector(redisClient), dynamicSource, notifier, settings);
    }

    /** Test-only seam: inject a fake {@link PubSubConnector} instead of a real Lettuce client. */
    FlagChangeSubscriber(PubSubConnector connector, RedisFlagSource dynamicSource,
                         FlagChangeNotifier notifier, FeatureSettings settings) {
        this.connector = connector;
        this.dynamicSource = dynamicSource;
        this.channel = notifier.channel();
        this.convergenceTracker = new ConvergenceTracker(settings.getConvergenceAlertThreshold());
        this.reconnectBaseDelay = settings.getPubsubReconnectBaseDelay();
        this.reconnectMaxDelay = settings.getPubsubReconnectMaxDelay();
    }

    @PostConstruct
    void start() {
        trySubscribe();
    }

    private void trySubscribe() {
        if (shuttingDown) {
            return;
        }
        PubSubConnector.Connection candidate = null;
        try {
            candidate = connector.connect();
            candidate.addListener(this::onMessage);
            candidate.subscribe(channel);
            connection = candidate;
            reconnectAttempts.set(0);
            LOG.info("Subscribed to feature-change channel {}", channel);
        } catch (Exception e) {
            closeQuietly(candidate); // never leak a connection that opened but failed to subscribe
            int attempt = reconnectAttempts.incrementAndGet();
            Duration delay = ReconnectBackoff.nextDelay(attempt, reconnectBaseDelay, reconnectMaxDelay, reconnectRandom);
            LOG.warn("feature-change subscribe failed; retrying in {} ({})", delay, e.getMessage());
            scheduler.schedule(this::trySubscribe, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void onMessage(String channel, String payload) {
        ChangeMessage message = ChangeMessage.parse(payload);
        if ("*".equals(message.flagName())) {
            dynamicSource.invalidateAll();
        } else {
            dynamicSource.invalidate(message.flagName());
        }
        if (message.publishedAtMillis() != null) {
            long latencyMillis = Math.max(0, System.currentTimeMillis() - message.publishedAtMillis());
            convergenceTracker.record(message.flagName(), Duration.ofMillis(latencyMillis));
        }
    }

    /** FTR-03: the most recently observed change-propagation latency for this instance, if any. */
    public Optional<Duration> lastConvergenceLatency() {
        return convergenceTracker.lastLatency();
    }

    /** FTR-03: whether the most recently observed convergence exceeded the approved limit. */
    public boolean isConvergenceDegraded() {
        return convergenceTracker.isLastDegraded();
    }

    private static void closeQuietly(PubSubConnector.Connection candidate) {
        if (candidate != null) {
            try {
                candidate.close();
            } catch (Exception ignored) {
                // best-effort cleanup of a connection we're already abandoning
            }
        }
    }

    @PreDestroy
    void close() {
        shuttingDown = true;
        scheduler.shutdownNow();
        closeQuietly(connection);
    }
}
