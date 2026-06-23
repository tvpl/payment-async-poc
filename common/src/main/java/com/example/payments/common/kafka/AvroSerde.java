package com.example.payments.common.kafka;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.apache.avro.specific.SpecificRecord;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper over the Apicurio Avro Kafka serde, used everywhere we cross the Kafka
 * boundary. Headers are disabled so the schema id is embedded <em>inside</em> the value
 * bytes — making the bytes self-describing, which is what lets the SBUS store them in the
 * transactional outbox and republish them untouched.
 *
 * <p>The Apicurio serializer/deserializer are <strong>not</strong> thread-safe, so each
 * thread gets its own instance via {@link ThreadLocal}. This removes the global lock that
 * a single shared instance would require and lets virtual threads / consumer threads
 * encode/decode concurrently without contention.
 */
@Singleton
public class AvroSerde {

    private final Map<String, Object> serializerConfig;
    private final Map<String, Object> deserializerConfig;

    private final ThreadLocal<AvroKafkaSerializer<SpecificRecord>> serializer =
            ThreadLocal.withInitial(this::newSerializer);
    private final ThreadLocal<AvroKafkaDeserializer<SpecificRecord>> deserializer =
            ThreadLocal.withInitial(this::newDeserializer);

    public AvroSerde(@Value("${apicurio.registry.url:`http://localhost:8085/apis/registry/v2`}") String registryUrl) {
        Map<String, Object> common = new HashMap<>();
        common.put("apicurio.registry.url", registryUrl);
        common.put("apicurio.registry.headers.enabled", false);

        this.serializerConfig = new HashMap<>(common);
        this.serializerConfig.put("apicurio.registry.auto-register", true);

        this.deserializerConfig = new HashMap<>(common);
        this.deserializerConfig.put("apicurio.registry.use-specific-avro-reader", true);
    }

    public byte[] serialize(String topic, SpecificRecord record) {
        return serializer.get().serialize(topic, record);
    }

    @SuppressWarnings("unchecked")
    public <T extends SpecificRecord> T deserialize(String topic, byte[] data) {
        return (T) deserializer.get().deserialize(topic, data);
    }

    private AvroKafkaSerializer<SpecificRecord> newSerializer() {
        AvroKafkaSerializer<SpecificRecord> s = new AvroKafkaSerializer<>();
        s.configure(serializerConfig, false);
        return s;
    }

    private AvroKafkaDeserializer<SpecificRecord> newDeserializer() {
        AvroKafkaDeserializer<SpecificRecord> d = new AvroKafkaDeserializer<>();
        d.configure(deserializerConfig, false);
        return d;
    }
}
