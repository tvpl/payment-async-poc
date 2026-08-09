package com.example.payments.common.kafka;

import com.example.payments.common.avro.PaymentRequest;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvroSerdeUnitTest {

    @Test
    void virtualThreadConcurrencyCreatesOnlyTheFixedCodecCapacity() throws Exception {
        var created = new AtomicInteger();
        try (var serde = new AvroSerde(3, Duration.ofSeconds(1), () -> {
            created.incrementAndGet();
            return new StubCodec();
        }); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<byte[]>>();
            for (int index = 0; index < 100; index++) {
                futures.add(executor.submit(() -> serde.serialize("topic", record())));
            }

            for (var future : futures) {
                assertArrayEquals(new byte[]{1, 2, 3}, future.get(1, TimeUnit.SECONDS));
            }
            assertEquals(3, created.get());
            assertEquals(new AvroSerde.PoolSnapshot(3, 3, 0, 0), serde.poolSnapshot());
        }
    }

    @Test
    void saturatedPoolFailsWithinTheAcquireBudgetAndCountsTimeout() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var serde = new AvroSerde(1, Duration.ofMillis(25), () -> new BlockingCodec(entered, release));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var borrowed = executor.submit(() -> serde.serialize("topic", record()));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            var failure = assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                    assertThrows(AvroCodecUnavailableException.class,
                            () -> serde.serialize("topic", record())));

            assertEquals("Avro codec pool exhausted after 25 ms", failure.getMessage());
            assertEquals(new AvroSerde.PoolSnapshot(1, 0, 1, 1), serde.poolSnapshot());
            release.countDown();
            assertArrayEquals(new byte[]{1, 2, 3}, borrowed.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void codecReturnsToPoolAfterSerializationFailure() {
        try (var serde = new AvroSerde(1, Duration.ofMillis(25), FailingCodec::new)) {
            assertThrows(IllegalStateException.class, () -> serde.serialize("topic", record()));

            assertEquals(new AvroSerde.PoolSnapshot(1, 1, 0, 0), serde.poolSnapshot());
        }
    }

    @Test
    void deserializationReturnsTheCodecResultAndRestoresCapacity() {
        var expected = record();
        try (var serde = new AvroSerde(1, Duration.ofMillis(25), () -> new StubCodec(expected))) {
            SpecificRecord actual = serde.deserialize("topic", new byte[]{9});

            assertSame(expected, actual);
            assertEquals(new AvroSerde.PoolSnapshot(1, 1, 0, 0), serde.poolSnapshot());
        }
    }

    @Test
    void rejectsUnboundedPoolConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new AvroSerde(0, Duration.ofMillis(25), StubCodec::new));
        assertThrows(IllegalArgumentException.class,
                () -> new AvroSerde(1, Duration.ZERO, StubCodec::new));
    }

    @Test
    void closeClosesEveryCreatedCodec() {
        var closed = new AtomicInteger();
        var serde = new AvroSerde(4, Duration.ofMillis(25), () -> new StubCodec(null, closed));

        serde.close();

        assertEquals(4, closed.get());
    }

    private static PaymentRequest record() {
        return PaymentRequest.newBuilder()
                .setMerchantId("merchant-1")
                .setAmount("10.00")
                .setCurrency("BRL")
                .setPaymentMethod("CREDIT_CARD")
                .setBrand("VISA")
                .setInstallments(1)
                .setCaptureMode("AUTHORIZE")
                .build();
    }

    private static class StubCodec implements AvroSerde.Codec {
        private final SpecificRecord result;
        private final AtomicInteger closed;

        private StubCodec() {
            this(null, new AtomicInteger());
        }

        private StubCodec(SpecificRecord result) {
            this(result, new AtomicInteger());
        }

        private StubCodec(SpecificRecord result, AtomicInteger closed) {
            this.result = result;
            this.closed = closed;
        }

        @Override
        public byte[] serialize(String topic, SpecificRecord record) {
            return new byte[]{1, 2, 3};
        }

        @Override
        public SpecificRecord deserialize(String topic, byte[] data) {
            return result;
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    private static final class BlockingCodec extends StubCodec {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingCodec(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public byte[] serialize(String topic, SpecificRecord record) {
            entered.countDown();
            try {
                if (!release.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return super.serialize(topic, record);
        }
    }

    private static final class FailingCodec extends StubCodec {
        @Override
        public byte[] serialize(String topic, SpecificRecord record) {
            throw new IllegalStateException("synthetic codec failure");
        }
    }
}
