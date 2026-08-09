package com.example.payments.coremock;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Sources;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.ProcessPaymentSimulationCommandPayload;
import com.example.payments.common.model.SimulationResult;
import io.micronaut.context.ApplicationContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Testcontainers
class CoreRedeliveryIT {

    private static final String CORE_GROUP = "payment-core-mock";
    private static final TopicPartition COMMAND_PARTITION = new TopicPartition(Topics.CORE_COMMAND, 0);
    private static final GenericContainer<?> APICURIO =
            new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry-mem:2.6.2.Final"))
                    .withExposedPorts(8080);

    static {
        APICURIO.start();
    }

    @Test
    void duplicateCommandProducesEquivalentResponsePayloads() throws Exception {
        try (KafkaContainer kafka = startedKafka();
             AdminClient admin = admin(kafka);
             ApplicationContext ignored = startApplication(kafka, registryUrl(), 0, 0);
             AvroSerde serde = new AvroSerde(registryUrl());
             KafkaProducer<String, byte[]> producer = producer(kafka);
             KafkaConsumer<String, byte[]> responseConsumer = responseConsumer(kafka)) {
            awaitAssignment(admin);
            responseConsumer.subscribe(List.of(Topics.CORE_RESPONSE));
            responseConsumer.poll(Duration.ofMillis(300));
            EventEnvelope<ProcessPaymentSimulationCommandPayload> command = command("duplicate");

            send(producer, serde, command);
            send(producer, serde, command);

            List<CorePaymentSimulationResponsePayload> responses =
                    pollResponses(responseConsumer, serde, command.requestId(), 2);
            assertEquals(2, responses.size());
            assertEquals(responses.get(0), responses.get(1));
        }
    }

    @Test
    void deterministicDeclinePreservesDocumentedPayload() throws Exception {
        try (KafkaContainer kafka = startedKafka();
             AdminClient admin = admin(kafka);
             ApplicationContext ignored = startApplication(kafka, registryUrl(), 100, 0);
             AvroSerde serde = new AvroSerde(registryUrl());
             KafkaProducer<String, byte[]> producer = producer(kafka);
             KafkaConsumer<String, byte[]> responseConsumer = responseConsumer(kafka)) {
            awaitAssignment(admin);
            responseConsumer.subscribe(List.of(Topics.CORE_RESPONSE));
            responseConsumer.poll(Duration.ofMillis(300));
            EventEnvelope<ProcessPaymentSimulationCommandPayload> command = command("decline");

            send(producer, serde, command);

            CorePaymentSimulationResponsePayload response =
                    pollResponses(responseConsumer, serde, command.requestId(), 1).getFirst();
            assertEquals(SimulationResult.DECLINED, response.status());
            assertEquals("51", response.errorCode());
            assertEquals("Insufficient funds", response.errorMessage());
            assertNull(response.authorizationCode());
        }
    }

    @Test
    void malformedAvroDoesNotAdvanceTheCommittedOffset() throws Exception {
        try (KafkaContainer kafka = startedKafka();
             AdminClient admin = admin(kafka);
             ApplicationContext ignored = startApplication(kafka, registryUrl(), 0, 0);
             AvroSerde serde = new AvroSerde(registryUrl());
             KafkaProducer<String, byte[]> producer = producer(kafka);
             KafkaConsumer<String, byte[]> responseConsumer = responseConsumer(kafka)) {
            awaitAssignment(admin);
            responseConsumer.subscribe(List.of(Topics.CORE_RESPONSE));
            responseConsumer.poll(Duration.ofMillis(300));
            completeBarrier(producer, responseConsumer, serde, admin);

            producer.send(new ProducerRecord<>(Topics.CORE_COMMAND, "poison", new byte[]{1, 2, 3})).get();
            EventEnvelope<ProcessPaymentSimulationCommandPayload> afterPoison = command("after-poison");
            send(producer, serde, afterPoison);

            assertOffsetRemains(admin, 1L);
            assertNull(pollResponse(responseConsumer, serde, afterPoison.requestId(), Duration.ofSeconds(2)));
        }
    }

    @Test
    void deterministicTransientFailureDoesNotAdvanceTheCommittedOffset() throws Exception {
        try (KafkaContainer kafka = startedKafka();
             AdminClient admin = admin(kafka);
             AvroSerde serde = new AvroSerde(registryUrl());
             KafkaProducer<String, byte[]> producer = producer(kafka);
             KafkaConsumer<String, byte[]> responseConsumer = responseConsumer(kafka)) {
            responseConsumer.subscribe(List.of(Topics.CORE_RESPONSE));
            responseConsumer.poll(Duration.ofMillis(300));
            try (ApplicationContext ignored = startApplication(kafka, registryUrl(), 0, 0)) {
                awaitAssignment(admin);
                completeBarrier(producer, responseConsumer, serde, admin);
            }

            try (ApplicationContext ignored = startApplication(kafka, registryUrl(), 0, 100)) {
                awaitAssignment(admin);
                EventEnvelope<ProcessPaymentSimulationCommandPayload> failed = command("transient-failure");
                send(producer, serde, failed);

                assertOffsetRemains(admin, 1L);
                assertNull(pollResponse(responseConsumer, serde, failed.requestId(), Duration.ofSeconds(2)));
            }
        }
    }

    @Test
    void registryOutageDoesNotAdvanceTheCommittedOffset() throws Exception {
        try (KafkaContainer kafka = startedKafka();
             AdminClient admin = admin(kafka);
             AvroSerde serde = new AvroSerde(registryUrl());
             KafkaProducer<String, byte[]> producer = producer(kafka);
             KafkaConsumer<String, byte[]> responseConsumer = responseConsumer(kafka)) {
            responseConsumer.subscribe(List.of(Topics.CORE_RESPONSE));
            responseConsumer.poll(Duration.ofMillis(300));
            try (ApplicationContext ignored = startApplication(kafka, registryUrl(), 0, 0)) {
                awaitAssignment(admin);
                completeBarrier(producer, responseConsumer, serde, admin);
            }

            try (ApplicationContext ignored = startApplication(kafka, "http://127.0.0.1:1/apis/registry/v2", 0, 0)) {
                awaitAssignment(admin);
                EventEnvelope<ProcessPaymentSimulationCommandPayload> failed = command("registry-outage");
                send(producer, serde, failed);

                assertOffsetRemains(admin, 1L);
                assertNull(pollResponse(responseConsumer, serde, failed.requestId(), Duration.ofSeconds(2)));
            }
        }
    }

    private static void completeBarrier(
            KafkaProducer<String, byte[]> producer,
            KafkaConsumer<String, byte[]> responseConsumer,
            AvroSerde serde,
            AdminClient admin) throws Exception {
        EventEnvelope<ProcessPaymentSimulationCommandPayload> barrier = command("barrier");
        send(producer, serde, barrier);
        pollResponses(responseConsumer, serde, barrier.requestId(), 1);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertEquals(1L, committedOffset(admin)));
    }

    private static void assertOffsetRemains(AdminClient admin, long expected) {
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(expected, committedOffset(admin)));
    }

    private static long committedOffset(AdminClient admin) throws Exception {
        var offset = admin.listConsumerGroupOffsets(CORE_GROUP)
                .partitionsToOffsetAndMetadata()
                .get()
                .get(COMMAND_PARTITION);
        return offset == null ? 0L : offset.offset();
    }

    private static void awaitAssignment(AdminClient admin) {
        await().atMost(Duration.ofSeconds(15)).until(() -> admin
                .describeConsumerGroups(List.of(CORE_GROUP))
                .describedGroups()
                .get(CORE_GROUP)
                .get()
                .members()
                .stream()
                .flatMap(member -> member.assignment().topicPartitions().stream())
                .anyMatch(COMMAND_PARTITION::equals));
    }

    private static List<CorePaymentSimulationResponsePayload> pollResponses(
            KafkaConsumer<String, byte[]> consumer,
            AvroSerde serde,
            String requestId,
            int count) {
        List<CorePaymentSimulationResponsePayload> responses = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (responses.size() < count && System.nanoTime() < deadline) {
            ConsumerRecord<String, byte[]> record = pollResponse(consumer, serde, requestId, Duration.ofMillis(500));
            if (record != null) {
                CorePaymentSimulationResponse avro = serde.deserialize(record.topic(), record.value());
                responses.add(AvroMapper.fromAvro(avro).payload());
            }
        }
        assertEquals(count, responses.size());
        return responses;
    }

    private static ConsumerRecord<String, byte[]> pollResponse(
            KafkaConsumer<String, byte[]> consumer,
            AvroSerde ignored,
            String requestId,
            Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                if (requestId.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    private static EventEnvelope<ProcessPaymentSimulationCommandPayload> command(String scenario) {
        String requestId = scenario + "-" + UUID.randomUUID();
        var payment = new PaymentSimulationRequestPayload(
                "MERCHANT-001", new BigDecimal("125.50"), "BRL",
                "CREDIT_CARD", "VISA", 3, "AUTHORIZE_AND_CAPTURE");
        return EventEnvelope.create(
                EventTypes.PROCESS_PAYMENT_SIMULATION_COMMAND,
                requestId,
                "correlation-" + requestId,
                requestId,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                Sources.SBUS,
                new ProcessPaymentSimulationCommandPayload("simulation-" + requestId, payment));
    }

    private static void send(
            KafkaProducer<String, byte[]> producer,
            AvroSerde serde,
            EventEnvelope<ProcessPaymentSimulationCommandPayload> command) throws Exception {
        byte[] bytes = serde.serialize(Topics.CORE_COMMAND, AvroMapper.toAvroCommand(command));
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(Topics.CORE_COMMAND, command.requestId(), bytes);
        record.headers().add(Headers.REQUEST_ID, command.requestId().getBytes(StandardCharsets.UTF_8));
        record.headers().add(Headers.TRACEPARENT, command.traceId().getBytes(StandardCharsets.UTF_8));
        producer.send(record).get();
    }

    private static KafkaContainer startedKafka() throws Exception {
        KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        kafka.start();
        try (AdminClient admin = admin(kafka)) {
            admin.createTopics(List.of(
                    new NewTopic(Topics.CORE_COMMAND, 1, (short) 1),
                    new NewTopic(Topics.CORE_RESPONSE, 1, (short) 1)))
                    .all()
                    .get();
        }
        return kafka;
    }

    private static ApplicationContext startApplication(
            KafkaContainer kafka,
            String registry,
            int declinePct,
            int failPct) {
        return ApplicationContext.run(Map.of(
                "kafka.bootstrap.servers", kafka.getBootstrapServers(),
                "apicurio.registry.url", registry,
                "core.behavior.latency-min-ms", 0,
                "core.behavior.latency-max-ms", 0,
                "core.behavior.decline-pct", declinePct,
                "core.behavior.fail-pct", failPct,
                "core.behavior.seed", 20260808L,
                "otel.traces.exporter", "none"));
    }

    private static String registryUrl() {
        return "http://" + APICURIO.getHost() + ":" + APICURIO.getMappedPort(8080)
                + "/apis/registry/v2";
    }

    private static AdminClient admin(KafkaContainer kafka) {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    private static KafkaProducer<String, byte[]> producer(KafkaContainer kafka) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(properties);
    }

    private static KafkaConsumer<String, byte[]> responseConsumer(KafkaContainer kafka) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "response-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }
}
