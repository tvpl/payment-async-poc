package com.example.payments.common.kafka;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.avro.specific.SpecificRecord;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper over the Apicurio Avro Kafka serde, used everywhere we cross the
 * Kafka boundary. Headers are disabled so the schema id is embedded <em>inside</em>
 * the value bytes — this makes the bytes self-describing, which is what lets the
 * SBUS store them in the transactional outbox and republish them later untouched.
 *
 * <p>The single serializer/deserializer instances are shared; their methods are
 * synchronized for safety in this PoC (low throughput). A pool would remove the
 * contention in production.
 */
@Singleton
public class AvroSerde {

    private final AvroKafkaSerializer<SpecificRecord> serializer = new AvroKafkaSerializer<>();
    private final AvroKafkaDeserializer<SpecificRecord> deserializer = new AvroKafkaDeserializer<>();

    public AvroSerde(@Value("${apicurio.registry.url:`http://localhost:8085/apis/registry/v2`}") String registryUrl) {
        Map<String, Object> common = new HashMap<>();
        common.put("apicurio.registry.url", registryUrl);
        common.put("apicurio.registry.headers.enabled", false);

        Map<String, Object> serCfg = new HashMap<>(common);
        serCfg.put("apicurio.registry.auto-register", true);
        serializer.configure(serCfg, false);

        Map<String, Object> deCfg = new HashMap<>(common);
        deCfg.put("apicurio.registry.use-specific-avro-reader", true);
        deserializer.configure(deCfg, false);
    }

    public synchronized byte[] serialize(String topic, SpecificRecord record) {
        return serializer.serialize(topic, record);
    }

    @SuppressWarnings("unchecked")
    public synchronized <T extends SpecificRecord> T deserialize(String topic, byte[] data) {
        return (T) deserializer.deserialize(topic, data);
    }

    @PreDestroy
    void close() {
        serializer.close();
        deserializer.close();
    }
}
