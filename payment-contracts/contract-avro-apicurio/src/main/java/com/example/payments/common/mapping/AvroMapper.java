package com.example.payments.common.mapping;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.avro.CoreResponsePayload;
import com.example.payments.common.avro.PaymentRequest;
import com.example.payments.common.avro.PaymentSimulationCompleted;
import com.example.payments.common.avro.PaymentSimulationFailed;
import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.avro.ProcessPayload;
import com.example.payments.common.avro.ProcessPaymentSimulationCommand;
import com.example.payments.common.avro.SimulationResultPayload;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.Fees;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.ProcessPaymentSimulationCommandPayload;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Converts between the application POJOs ({@link EventEnvelope} + payloads) and the
 * Avro classes used on the Kafka wire. HTTP and Redis stay on POJOs/JSON; only the
 * Kafka boundary is Avro, so this mapper is the single translation point.
 */
public final class AvroMapper {

    private AvroMapper() {
    }

    // ---------------------------------------------------------------- Requested
    public static PaymentSimulationRequested toAvroRequested(EventEnvelope<PaymentSimulationRequestPayload> e) {
        return PaymentSimulationRequested.newBuilder()
                .setEventId(e.eventId())
                .setEventType(e.eventType())
                .setEventVersion(e.eventVersion())
                .setOccurredAt(e.occurredAt().toEpochMilli())
                .setRequestId(e.requestId())
                .setCorrelationId(e.correlationId())
                .setCausationId(e.causationId())
                .setTraceId(e.traceId())
                .setSource(e.source())
                .setPayload(toAvro(e.payload()))
                .build();
    }

    public static EventEnvelope<PaymentSimulationRequestPayload> fromAvro(PaymentSimulationRequested a) {
        return new EventEnvelope<>(a.getEventId(), a.getEventType(), a.getEventVersion(),
                Instant.ofEpochMilli(a.getOccurredAt()), a.getRequestId(), a.getCorrelationId(),
                a.getCausationId(), a.getTraceId(), a.getSource(), fromAvro(a.getPayload()));
    }

    // ------------------------------------------------------------------ Command
    public static ProcessPaymentSimulationCommand toAvroCommand(EventEnvelope<ProcessPaymentSimulationCommandPayload> e) {
        ProcessPaymentSimulationCommandPayload p = e.payload();
        ProcessPayload payload = ProcessPayload.newBuilder()
                .setSimulationId(p.simulationId())
                .setRequest(toAvro(p.request()))
                .build();
        return ProcessPaymentSimulationCommand.newBuilder()
                .setEventId(e.eventId())
                .setEventType(e.eventType())
                .setEventVersion(e.eventVersion())
                .setOccurredAt(e.occurredAt().toEpochMilli())
                .setRequestId(e.requestId())
                .setCorrelationId(e.correlationId())
                .setCausationId(e.causationId())
                .setTraceId(e.traceId())
                .setSource(e.source())
                .setPayload(payload)
                .build();
    }

    public static EventEnvelope<ProcessPaymentSimulationCommandPayload> fromAvro(ProcessPaymentSimulationCommand a) {
        ProcessPayload p = a.getPayload();
        var payload = new ProcessPaymentSimulationCommandPayload(p.getSimulationId(), fromAvro(p.getRequest()));
        return new EventEnvelope<>(a.getEventId(), a.getEventType(), a.getEventVersion(),
                Instant.ofEpochMilli(a.getOccurredAt()), a.getRequestId(), a.getCorrelationId(),
                a.getCausationId(), a.getTraceId(), a.getSource(), payload);
    }

    // ------------------------------------------------------------- Core response
    public static CorePaymentSimulationResponse toAvroCoreResponse(EventEnvelope<CorePaymentSimulationResponsePayload> e) {
        CorePaymentSimulationResponsePayload p = e.payload();
        CoreResponsePayload payload = CoreResponsePayload.newBuilder()
                .setSimulationId(p.simulationId())
                .setStatus(p.status())
                .setAuthorizationCode(p.authorizationCode())
                .setAmount(str(p.amount()))
                .setCurrency(p.currency())
                .setInstallments(orOne(p.installments()))
                .setFees(toAvro(p.fees()))
                .setSettlement(toAvro(p.settlement()))
                .setErrorCode(p.errorCode())
                .setErrorMessage(p.errorMessage())
                .build();
        return CorePaymentSimulationResponse.newBuilder()
                .setEventId(e.eventId())
                .setEventType(e.eventType())
                .setEventVersion(e.eventVersion())
                .setOccurredAt(e.occurredAt().toEpochMilli())
                .setRequestId(e.requestId())
                .setCorrelationId(e.correlationId())
                .setCausationId(e.causationId())
                .setTraceId(e.traceId())
                .setSource(e.source())
                .setPayload(payload)
                .build();
    }

    public static EventEnvelope<CorePaymentSimulationResponsePayload> fromAvro(CorePaymentSimulationResponse a) {
        CoreResponsePayload p = a.getPayload();
        var payload = new CorePaymentSimulationResponsePayload(
                p.getSimulationId(), p.getStatus(), p.getAuthorizationCode(), dec(p.getAmount()),
                p.getCurrency(), p.getInstallments(), fromAvro(p.getFees()), fromAvro(p.getSettlement()),
                p.getErrorCode(), p.getErrorMessage());
        return new EventEnvelope<>(a.getEventId(), a.getEventType(), a.getEventVersion(),
                Instant.ofEpochMilli(a.getOccurredAt()), a.getRequestId(), a.getCorrelationId(),
                a.getCausationId(), a.getTraceId(), a.getSource(), payload);
    }

    // ----------------------------------------------------------- Completed/Failed
    public static PaymentSimulationCompleted toAvroCompleted(EventEnvelope<SimulationResult> e) {
        return PaymentSimulationCompleted.newBuilder()
                .setEventId(e.eventId()).setEventType(e.eventType()).setEventVersion(e.eventVersion())
                .setOccurredAt(e.occurredAt().toEpochMilli()).setRequestId(e.requestId())
                .setCorrelationId(e.correlationId()).setCausationId(e.causationId()).setTraceId(e.traceId())
                .setSource(e.source()).setPayload(toAvro(e.payload())).build();
    }

    public static PaymentSimulationFailed toAvroFailed(EventEnvelope<SimulationResult> e) {
        return PaymentSimulationFailed.newBuilder()
                .setEventId(e.eventId()).setEventType(e.eventType()).setEventVersion(e.eventVersion())
                .setOccurredAt(e.occurredAt().toEpochMilli()).setRequestId(e.requestId())
                .setCorrelationId(e.correlationId()).setCausationId(e.causationId()).setTraceId(e.traceId())
                .setSource(e.source()).setPayload(toAvro(e.payload())).build();
    }

    public static EventEnvelope<SimulationResult> fromAvro(PaymentSimulationCompleted a) {
        return resultEnvelope(a.getEventId(), a.getEventType(), a.getEventVersion(), a.getOccurredAt(),
                a.getRequestId(), a.getCorrelationId(), a.getCausationId(), a.getTraceId(), a.getSource(),
                a.getPayload());
    }

    public static EventEnvelope<SimulationResult> fromAvro(PaymentSimulationFailed a) {
        return resultEnvelope(a.getEventId(), a.getEventType(), a.getEventVersion(), a.getOccurredAt(),
                a.getRequestId(), a.getCorrelationId(), a.getCausationId(), a.getTraceId(), a.getSource(),
                a.getPayload());
    }

    // ------------------------------------------------------------ payload records
    private static PaymentRequest toAvro(PaymentSimulationRequestPayload p) {
        return PaymentRequest.newBuilder()
                .setMerchantId(p.merchantId())
                .setAmount(str(p.amount()))
                .setCurrency(p.currency())
                .setPaymentMethod(p.paymentMethod())
                .setBrand(p.brand())
                .setInstallments(orOne(p.installments()))
                .setCaptureMode(p.captureMode())
                .build();
    }

    private static PaymentSimulationRequestPayload fromAvro(PaymentRequest a) {
        return new PaymentSimulationRequestPayload(a.getMerchantId(), dec(a.getAmount()), a.getCurrency(),
                a.getPaymentMethod(), a.getBrand(), a.getInstallments(), a.getCaptureMode());
    }

    private static SimulationResultPayload toAvro(SimulationResult r) {
        return SimulationResultPayload.newBuilder()
                .setSimulationId(r.simulationId())
                .setRequestId(r.requestId())
                .setStatus(r.status())
                .setAuthorizationCode(r.authorizationCode())
                .setAmount(str(r.amount()))
                .setCurrency(r.currency())
                .setInstallments(orOne(r.installments()))
                .setFees(toAvro(r.fees()))
                .setSettlement(toAvro(r.settlement()))
                .setErrorCode(r.errorCode())
                .setErrorMessage(r.errorMessage())
                .build();
    }

    private static EventEnvelope<SimulationResult> resultEnvelope(
            String eventId, String eventType, String eventVersion, long occurredAt,
            String requestId, String correlationId, String causationId, String traceId, String source,
            SimulationResultPayload p) {
        var result = new SimulationResult(p.getSimulationId(), p.getRequestId(), p.getStatus(),
                p.getAuthorizationCode(), dec(p.getAmount()), p.getCurrency(), p.getInstallments(),
                fromAvro(p.getFees()), fromAvro(p.getSettlement()), p.getErrorCode(), p.getErrorMessage());
        return new EventEnvelope<>(eventId, eventType, eventVersion, Instant.ofEpochMilli(occurredAt),
                requestId, correlationId, causationId, traceId, source, result);
    }

    private static com.example.payments.common.avro.Fees toAvro(Fees f) {
        if (f == null) {
            return null;
        }
        return com.example.payments.common.avro.Fees.newBuilder()
                .setMdr(str(f.mdr())).setInterchange(str(f.interchange())).setNetAmount(str(f.netAmount()))
                .build();
    }

    private static Fees fromAvro(com.example.payments.common.avro.Fees f) {
        if (f == null) {
            return null;
        }
        return new Fees(dec(f.getMdr()), dec(f.getInterchange()), dec(f.getNetAmount()));
    }

    private static com.example.payments.common.avro.Settlement toAvro(Settlement s) {
        if (s == null) {
            return null;
        }
        return com.example.payments.common.avro.Settlement.newBuilder()
                .setSettlementDate(s.settlementDate() == null ? null : s.settlementDate().toString())
                .setSettlementType(s.settlementType())
                .build();
    }

    private static Settlement fromAvro(com.example.payments.common.avro.Settlement s) {
        if (s == null) {
            return null;
        }
        LocalDate date = s.getSettlementDate() == null ? null : LocalDate.parse(s.getSettlementDate());
        return new Settlement(date, s.getSettlementType());
    }

    // --------------------------------------------------------------------- utils
    private static String str(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static BigDecimal dec(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static int orOne(Integer v) {
        return v == null ? 1 : v;
    }
}
