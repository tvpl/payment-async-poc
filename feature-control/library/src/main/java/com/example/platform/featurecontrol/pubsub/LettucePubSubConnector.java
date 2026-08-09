package com.example.platform.featurecontrol.pubsub;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.util.function.BiConsumer;

/** Production {@link PubSubConnector} backed by a real Lettuce {@link RedisClient}. */
public final class LettucePubSubConnector implements PubSubConnector {

    private final RedisClient client;

    public LettucePubSubConnector(RedisClient client) {
        this.client = client;
    }

    @Override
    public Connection connect() {
        return new LettuceConnection(client.connectPubSub());
    }

    private static final class LettuceConnection implements Connection {
        private final StatefulRedisPubSubConnection<String, String> raw;

        LettuceConnection(StatefulRedisPubSubConnection<String, String> raw) {
            this.raw = raw;
        }

        @Override
        public void addListener(BiConsumer<String, String> onMessage) {
            raw.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    onMessage.accept(channel, message);
                }
            });
        }

        @Override
        public void subscribe(String channel) {
            raw.sync().subscribe(channel);
        }

        @Override
        public boolean isOpen() {
            return raw.isOpen();
        }

        @Override
        public void close() {
            raw.close();
        }
    }
}
