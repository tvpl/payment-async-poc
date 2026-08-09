package com.example.payments.contracts.fixture;

import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.EventTypes;
import com.example.payments.common.events.Sources;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedArtifactContractTest {

    @Test
    void serializesAndDeserializesUsingOnlyPublishedArtifacts() throws Exception {
        var payload = new PaymentSimulationRequestPayload(
                "merchant-1", new BigDecimal("125.50"), "BRL", "CREDIT_CARD", "VISA", 3,
                "AUTHORIZE_AND_CAPTURE");
        var envelope = EventEnvelope.create(
                EventTypes.PAYMENT_SIMULATION_REQUESTED,
                "request-1", "correlation-1", "cause-1", "trace-1", Sources.API, payload);
        PaymentSimulationRequested avro = AvroMapper.toAvroRequested(envelope);

        var output = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(output, null);
        new SpecificDatumWriter<>(PaymentSimulationRequested.class).write(avro, encoder);
        encoder.flush();
        byte[] bytes = output.toByteArray();

        var decoder = DecoderFactory.get().binaryDecoder(bytes, null);
        var decoded = new SpecificDatumReader<>(PaymentSimulationRequested.class).read(null, decoder);
        var actual = AvroMapper.fromAvro(decoded);

        assertTrue(bytes.length > 0);
        assertEquals(envelope.eventId(), actual.eventId());
        assertEquals("PaymentSimulationRequested", actual.eventType());
        assertEquals("1.0", actual.eventVersion());
        assertEquals("request-1", actual.requestId());
        assertEquals("correlation-1", actual.correlationId());
        assertEquals("cause-1", actual.causationId());
        assertEquals("trace-1", actual.traceId());
        assertEquals("payment-simulation-api", actual.source());
        assertEquals("merchant-1", actual.payload().merchantId());
        assertEquals(new BigDecimal("125.50"), actual.payload().amount());
        assertEquals("BRL", actual.payload().currency());
        assertEquals("CREDIT_CARD", actual.payload().paymentMethod());
        assertEquals("VISA", actual.payload().brand());
        assertEquals(3, actual.payload().installments());
        assertEquals("AUTHORIZE_AND_CAPTURE", actual.payload().captureMode());
    }
}
