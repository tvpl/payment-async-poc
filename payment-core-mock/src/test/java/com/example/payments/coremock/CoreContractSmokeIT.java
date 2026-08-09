package com.example.payments.coremock;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.ProcessPaymentSimulationCommandPayload;
import com.example.payments.common.model.SimulationResult;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class CoreContractSmokeIT {

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    static {
        KAFKA.start();
        APICURIO.start();
    }

    @Test
    void consumesPublishedCommandAndPreservesResponseContract() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var payment = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL",
                "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");
        var commandPayload = new ProcessPaymentSimulationCommandPayload("simulation-1", payment);
        var command = EventEnvelope.create(
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND,
                requestId,
                correlationId,
                requestId,
                traceparent,
                Sources.SBUS,
                commandPayload);

        try (ApplicationContext ignored = ApplicationContext.run(applicationProperties());
             AvroSerde serde = new AvroSerde(registryUrl());
             KafkaProducer<String, byte[]> producer = producer();
             KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(Topics.CORE_RESPONSE));
            consumer.poll(Duration.ofMillis(500));

            byte[] commandBytes = serde.serialize(
                    Topics.CORE_COMMAND,
                    AvroMapper.toAvroCommand(command));
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(Topics.CORE_COMMAND, requestId, commandBytes);
            record.headers().add(Headers.REQUEST_ID, requestId.getBytes(StandardCharsets.UTF_8));
            record.headers().add(Headers.TRACEPARENT, traceparent.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();

            ConsumerRecord<String, byte[]> responseRecord = poll(consumer);
            assertNotNull(responseRecord);
            assertEquals(Topics.CORE_RESPONSE, responseRecord.topic());
            assertEquals(requestId, responseRecord.key());
            assertEquals(requestId, header(responseRecord, Headers.REQUEST_ID));
            String responseTraceparent = header(responseRecord, Headers.TRACEPARENT);
            assertNotNull(responseTraceparent);
            assertTrue(responseTraceparent.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"));
            assertEquals(traceId(traceparent), traceId(responseTraceparent));

            CorePaymentSimulationResponse avroResponse =
                    serde.deserialize(Topics.CORE_RESPONSE, responseRecord.value());
            var response = AvroMapper.fromAvro(avroResponse);
            assertEquals(EventTypes.CORE_PAYMENT_SIMULATION_RESPONSE, response.eventType());
            assertEquals(Sources.CORE, response.source());
            assertEquals(requestId, response.requestId());
            assertEquals(correlationId, response.correlationId());
            assertEquals(command.eventId(), response.causationId());
            assertEquals("simulation-1", response.payload().simulationId());
            assertEquals(SimulationResult.APPROVED, response.payload().status());
            assertEquals(new BigDecimal("125.50"), response.payload().amount());
        }
    }

    private static Map<String, Object> applicationProperties() {
        return Map.of(
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "apicurio.registry.url", registryUrl(),
                "core.behavior.latency-min-ms", 0,
                "core.behavior.latency-max-ms", 0,
                "core.behavior.decline-pct", 0,
                "core.behavior.fail-pct", 0,
                "otel.traces.exporter", "none");
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080)
                + "/apis/registry/v2";
    }

    private static KafkaProducer<String, byte[]> producer() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(properties);
    }

    private static KafkaConsumer<String, byte[]> consumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "core-smoke-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }

    private static ConsumerRecord<String, byte[]> poll(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                if (Topics.CORE_RESPONSE.equals(record.topic())) {
                    return record;
                }
            }
        }
        return null;
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String traceId(String traceparent) {
        return traceparent.substring(3, 35);
    }
}
