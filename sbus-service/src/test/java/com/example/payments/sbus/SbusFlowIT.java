package com.example.payments.sbus;

import com.example.payments.common.avro.ProcessPaymentSimulationCommand;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import com.example.payments.sbus.domain.SbusMessageStatus;
import com.example.payments.sbus.repository.PaymentSbusMessageRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end SBUS flow against real Postgres + Kafka + Apicurio registry (Avro):
 * requested -> persisted + outbox -> core.command -> core.response -> completed.
 * Requires a running Docker daemon.
 */
@MicronautTest(startApplication = true)
@Testcontainers
class SbusFlowIT implements TestPropertyProvider {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    static {
        POSTGRES.start();
        KAFKA.start();
        APICURIO.start();
    }

    @Inject
    PaymentSbusMessageRepository messageRepository;

    static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080) + "/apis/registry/v2";
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "apicurio.registry.url", registryUrl(),
                "datasources.default.url", POSTGRES.getJdbcUrl() + "?stringtype=unspecified",
                "datasources.default.username", POSTGRES.getUsername(),
                "datasources.default.password", POSTGRES.getPassword(),
                "sbus.outbox.initial-delay", "200ms",
                "sbus.outbox.poll-interval", "200ms");
    }

    @Test
    void processesRequestedThroughOutboxAndCoreResponse() throws Exception {
        AvroSerde serde = new AvroSerde(registryUrl());
        String requestId = UUID.randomUUID().toString();
        var payload = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");
        var requested = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_REQUESTED,
                requestId, UUID.randomUUID().toString(), requestId, "trace", Sources.API, payload);

        try (KafkaProducer<String, byte[]> producer = producer();
             KafkaConsumer<String, byte[]> coreCommandConsumer = consumer("core-cmd-test");
             KafkaConsumer<String, byte[]> completedConsumer = consumer("completed-test")) {

            coreCommandConsumer.subscribe(List.of(Topics.CORE_COMMAND));
            completedConsumer.subscribe(List.of(Topics.COMPLETED));

            producer.send(new ProducerRecord<>(Topics.REQUESTED, requestId,
                    serde.serialize(Topics.REQUESTED, AvroMapper.toAvroRequested(requested)))).get();

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertTrue(messageRepository.findByRequestId(requestId).isPresent()));

            ConsumerRecord<String, byte[]> coreCommand = poll(coreCommandConsumer, Topics.CORE_COMMAND);
            assertNotNull(coreCommand);
            ProcessPaymentSimulationCommand command = serde.deserialize(Topics.CORE_COMMAND, coreCommand.value());
            String simulationId = AvroMapper.fromAvro(command).payload().simulationId();

            var coreResponse = new CorePaymentSimulationResponsePayload(
                    simulationId, SimulationResult.APPROVED, "123456",
                    new BigDecimal("125.50"), "BRL", 3,
                    new Fees(new BigDecimal("2.49"), new BigDecimal("1.25"), new BigDecimal("122.38")),
                    new Settlement(LocalDate.now().plusDays(1), "D+1"), null, null);
            var responseEnvelope = requested.deriveAs(
                    EventTypes.CORE_PAYMENT_SIMULATION_RESPONSE, Sources.CORE, coreResponse);
            byte[] bytes = serde.serialize(Topics.CORE_RESPONSE, AvroMapper.toAvroCoreResponse(responseEnvelope));
            producer.send(new ProducerRecord<>(Topics.CORE_RESPONSE, requestId, bytes)).get();

            ConsumerRecord<String, byte[]> completed = poll(completedConsumer, Topics.COMPLETED);
            assertNotNull(completed);

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertEquals(SbusMessageStatus.COMPLETED,
                            messageRepository.findByRequestId(requestId).orElseThrow().getStatus()));
        }
    }

    private ConsumerRecord<String, byte[]> poll(KafkaConsumer<String, byte[]> consumer, String topic) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                if (record.topic().equals(topic)) {
                    return record;
                }
            }
        }
        return null;
    }

    private KafkaProducer<String, byte[]> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, byte[]> consumer(String group) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
