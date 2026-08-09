package com.example.platform.featurecontrol.pubsub;

import java.util.function.BiConsumer;

/**
 * The minimal pub/sub connection lifecycle {@code FlagChangeSubscriber} needs from Redis, narrowed
 * so its reconnect/leak-prevention logic (FTR-03) is unit-testable with a hand-written fake instead
 * of the full Lettuce pub/sub API surface — the same pattern as {@code source.FlagKeyReader} (T48).
 */
public interface PubSubConnector {

    /** Opens a new connection. May throw; the caller owns closing it on any subsequent failure. */
    Connection connect();

    interface Connection extends AutoCloseable {

        /** Registers the message callback. Must be called before {@link #subscribe(String)}. */
        void addListener(BiConsumer<String, String> onMessage);

        /**
         * Subscribes to {@code channel}. May throw — the connection is still open when this throws;
         * the caller (never this method) is responsible for closing it, so callers must always
         * {@code close()} on any failure path instead of leaking the connection.
         */
        void subscribe(String channel);

        boolean isOpen();

        @Override
        void close();
    }
}
