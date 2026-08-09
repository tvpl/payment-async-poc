package com.example.payments.common.events;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.avro.CoreResponsePayload;
import com.example.payments.common.avro.Fees;
import com.example.payments.common.avro.PaymentRequest;
import com.example.payments.common.avro.PaymentSimulationCompleted;
import com.example.payments.common.avro.PaymentSimulationFailed;
import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.avro.ProcessPayload;
import com.example.payments.common.avro.ProcessPaymentSimulationCommand;
import com.example.payments.common.avro.Settlement;
import com.example.payments.common.avro.SimulationResultPayload;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvroSchemaContractTest {

    @Test
    void generatesEveryPublishedAvroRecord() {
        assertEquals(Set.of(
                "CorePaymentSimulationResponse",
                "CoreResponsePayload",
                "Fees",
                "PaymentRequest",
                "PaymentSimulationCompleted",
                "PaymentSimulationFailed",
                "PaymentSimulationRequested",
                "ProcessPayload",
                "ProcessPaymentSimulationCommand",
                "Settlement",
                "SimulationResultPayload"
        ), Set.of(
                CorePaymentSimulationResponse.getClassSchema().getName(),
                CoreResponsePayload.getClassSchema().getName(),
                Fees.getClassSchema().getName(),
                PaymentRequest.getClassSchema().getName(),
                PaymentSimulationCompleted.getClassSchema().getName(),
                PaymentSimulationFailed.getClassSchema().getName(),
                PaymentSimulationRequested.getClassSchema().getName(),
                ProcessPayload.getClassSchema().getName(),
                ProcessPaymentSimulationCommand.getClassSchema().getName(),
                Settlement.getClassSchema().getName(),
                SimulationResultPayload.getClassSchema().getName()
        ));
    }

    @Test
    void preservesEnvelopeFieldOrderOnEveryEvent() {
        List<String> expected = List.of(
                "eventId", "eventType", "eventVersion", "occurredAt", "requestId",
                "correlationId", "causationId", "traceId", "source", "payload"
        );

        for (Schema schema : List.of(
                PaymentSimulationRequested.getClassSchema(),
                ProcessPaymentSimulationCommand.getClassSchema(),
                CorePaymentSimulationResponse.getClassSchema(),
                PaymentSimulationCompleted.getClassSchema(),
                PaymentSimulationFailed.getClassSchema()
        )) {
            assertEquals(expected, schema.getFields().stream().map(Schema.Field::name).toList(), schema.getName());
        }
    }
}
