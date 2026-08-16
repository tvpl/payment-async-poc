package com.example.payments.api.filter;

import com.example.payments.api.config.ApiRedisCommandsProvider;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Test-only substitute for {@link ApiRedisCommandsProvider} that records, for every call, whether
 * the caller was on a virtual thread ({@link Thread#isVirtual()}). Netty's event loop threads are
 * always platform threads, so this is a direct, framework-version-independent proof that
 * {@code ConcurrencyLimitFilter}'s Redis round-trip ran off the event loop (BUDG-03/04).
 *
 * <p>Guarded by {@code test.capture-redis-thread=true} so it never activates outside the one IT
 * that opts in, leaving every other test's application context on the real provider.
 */
@Singleton
@Replaces(ApiRedisCommandsProvider.class)
@Requires(property = "test.capture-redis-thread", value = "true")
public class ThreadCapturingRedisCommandsProvider extends ApiRedisCommandsProvider {

    static final BlockingQueue<Boolean> VIRTUAL_THREAD_FLAGS = new LinkedBlockingQueue<>();

    public ThreadCapturingRedisCommandsProvider(RedisClient client) {
        super(client);
    }

    @Override
    public RedisCommands<String, String> commands() {
        VIRTUAL_THREAD_FLAGS.add(Thread.currentThread().isVirtual());
        return super.commands();
    }
}
