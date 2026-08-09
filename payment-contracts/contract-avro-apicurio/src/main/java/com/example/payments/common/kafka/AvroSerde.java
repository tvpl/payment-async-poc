package com.example.payments.common.kafka;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import org.apache.avro.specific.SpecificRecord;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounded adapter around the non-thread-safe Apicurio serializer and deserializer.
 *
 * <p>Each codec pair is borrowed for one operation and always returned. The fixed
 * pool prevents virtual threads from creating one registry client per request.
 */
public final class AvroSerde implements AutoCloseable {

    static final int DEFAULT_POOL_SIZE = 8;
    static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofMillis(250);

    private final ArrayBlockingQueue<Codec> codecs;
    private final Duration acquireTimeout;
    private final AtomicLong timeouts = new AtomicLong();

    public AvroSerde(String registryUrl) {
        this(registryUrl, DEFAULT_POOL_SIZE, DEFAULT_ACQUIRE_TIMEOUT, true);
    }

    public AvroSerde(
            String registryUrl,
            int poolSize,
            Duration acquireTimeout,
            boolean autoRegister) {
        this(poolSize, acquireTimeout, () -> new ApicurioCodec(registryUrl, autoRegister));
    }

    AvroSerde(int poolSize, Duration acquireTimeout, Supplier<Codec> codecFactory) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be greater than zero");
        }
        if (acquireTimeout == null || acquireTimeout.isZero() || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout must be greater than zero");
        }
        Objects.requireNonNull(codecFactory, "codecFactory");

        this.codecs = new ArrayBlockingQueue<>(poolSize);
        this.acquireTimeout = acquireTimeout;
        for (int index = 0; index < poolSize; index++) {
            codecs.add(Objects.requireNonNull(codecFactory.get(), "codecFactory returned null"));
        }
    }

    public byte[] serialize(String topic, SpecificRecord record) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(record, "record");
        return withCodec(codec -> codec.serialize(topic, record));
    }

    @SuppressWarnings("unchecked")
    public <T extends SpecificRecord> T deserialize(String topic, byte[] data) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(data, "data");
        return (T) withCodec(codec -> codec.deserialize(topic, data));
    }

    public PoolSnapshot poolSnapshot() {
        int available = codecs.size();
        int capacity = available + codecs.remainingCapacity();
        return new PoolSnapshot(capacity, available, capacity - available, timeouts.get());
    }

    private <T> T withCodec(Function<Codec, T> action) {
        Codec codec;
        try {
            codec = codecs.poll(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AvroCodecUnavailableException("Interrupted while waiting for an Avro codec", interrupted);
        }

        if (codec == null) {
            timeouts.incrementAndGet();
            throw new AvroCodecUnavailableException(
                    "Avro codec pool exhausted after " + acquireTimeout.toMillis() + " ms");
        }

        try {
            return action.apply(codec);
        } finally {
            if (!codecs.offer(codec)) {
                throw new IllegalStateException("Avro codec pool rejected a borrowed codec");
            }
        }
    }

    @Override
    public void close() {
        Codec codec;
        while ((codec = codecs.poll()) != null) {
            codec.close();
        }
    }

    public record PoolSnapshot(int capacity, int available, int borrowed, long timeouts) {
    }

    interface Codec extends AutoCloseable {
        byte[] serialize(String topic, SpecificRecord record);

        SpecificRecord deserialize(String topic, byte[] data);

        @Override
        default void close() {
        }
    }

    private static final class ApicurioCodec implements Codec {
        private final AvroKafkaSerializer<SpecificRecord> serializer = new AvroKafkaSerializer<>();
        private final AvroKafkaDeserializer<SpecificRecord> deserializer = new AvroKafkaDeserializer<>();

        private ApicurioCodec(String registryUrl, boolean autoRegister) {
            Map<String, Object> common = new HashMap<>();
            common.put("apicurio.registry.url", registryUrl);
            common.put("apicurio.registry.headers.enabled", false);

            Map<String, Object> serializerConfig = new HashMap<>(common);
            serializerConfig.put("apicurio.registry.auto-register", autoRegister);
            serializer.configure(serializerConfig, false);

            Map<String, Object> deserializerConfig = new HashMap<>(common);
            deserializerConfig.put("apicurio.registry.use-specific-avro-reader", true);
            deserializer.configure(deserializerConfig, false);
        }

        @Override
        public byte[] serialize(String topic, SpecificRecord record) {
            return serializer.serialize(topic, record);
        }

        @Override
        public SpecificRecord deserialize(String topic, byte[] data) {
            return deserializer.deserialize(topic, data);
        }

        @Override
        public void close() {
            serializer.close();
            deserializer.close();
        }
    }
}
