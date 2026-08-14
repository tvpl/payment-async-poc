package com.example.payments.sbus.kafka;

import com.example.payments.common.avro.CorePaymentSimulationResponse;
import com.example.payments.common.avro.PaymentSimulationRequested;
import com.example.payments.common.events.EventEnvelope;
import com.example.payments.common.events.Headers;
import com.example.payments.common.events.Topics;
import com.example.payments.common.kafka.AvroCodecUnavailableException;
import com.example.payments.common.kafka.AvroSerde;
import com.example.payments.common.mapping.AvroMapper;
import com.example.payments.common.model.CorePaymentSimulationResponsePayload;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.sbus.service.PaymentSimulationService;
import com.example.payments.sbus.support.KafkaHeaders;
import com.example.payments.sbus.support.Mdc;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.Map;

/**
 * Shared decode + route logic used by both the main consumers and the retry consumer.
 *
 * <p>Deserialization/validation failures are surfaced as {@link PoisonMessageException}
 * (route straight to DLQ); failures from the business processing propagate as ordinary
 * {@link RuntimeException} (eligible for retry).
 *
 * <p>Not every decode failure is poison, though (AUD-09): a Schema Registry outage during
 * deserialization is a connectivity problem, not a defect in the payload. Before this fix, ANY
 * exception from {@link AvroSerde#deserialize} — including a registry that simply isn't
 * reachable right now — was classified poison and dead-lettered, which meant a perfectly valid
 * payment got dropped straight to the DLQ during a routine registry restart. Connectivity
 * failures surface as {@link RegistryUnavailableException} instead, which the consumers route to
 * the transient/retry path exactly like {@link AvroCodecUnavailableException} already does.
 * Detection walks the cause chain for either: a raw {@link java.io.IOException} (a client-side
 * network failure that never even reached the Apicurio REST client), or an Apicurio
 * {@code RestClientException} whose embedded {@code Error} has no {@code errorCode} — the
 * client's own signal for "no HTTP response was ever received", as opposed to a real HTTP error
 * response from the registry (which always carries a status code). The Apicurio client does NOT
 * preserve the original {@code IOException} as a Java {@code cause} for a connectivity failure —
 * {@code ErrorHandler#parseError} synthesizes a fresh exception from just the failing exception's
 * class name and message — so the errorCode check is the reliable signal in practice; the raw
 * {@code IOException} check is a defensive fallback. A genuinely undecodable payload never
 * touches the network in the first place, so neither signal fires — it still lands on
 * {@link PoisonMessageException}.
 */
@Singleton
public class SimulationMessageHandler {

    private final AvroSerde avroSerde;
    private final PaymentSimulationService service;

    public SimulationMessageHandler(AvroSerde avroSerde, PaymentSimulationService service) {
        this.avroSerde = avroSerde;
        this.service = service;
    }

    public void handle(String originTopic, ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = KafkaHeaders.toMap(record);
        switch (originTopic) {
            case Topics.REQUESTED -> handleRequested(record, headers);
            case Topics.CORE_RESPONSE -> handleCoreResponse(record);
            default -> throw new PoisonMessageException("Unknown origin topic: " + originTopic, null);
        }
    }

    private void handleRequested(ConsumerRecord<String, byte[]> record, Map<String, String> headers) {
        EventEnvelope<PaymentSimulationRequestPayload> env;
        try {
            PaymentSimulationRequested avro = avroSerde.deserialize(Topics.REQUESTED, record.value());
            env = AvroMapper.fromAvro(avro);
            if (env.requestId() == null || env.payload() == null
                    || env.payload().amount() == null || env.payload().merchantId() == null
                    || env.payload().currency() == null) {
                throw new IllegalArgumentException("missing required fields");
            }
        } catch (AvroCodecUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception e) {
            if (isRegistryConnectivityFailure(e)) {
                throw new RegistryUnavailableException(
                        "Registry unavailable while decoding PaymentSimulationRequested", e);
            }
            throw new PoisonMessageException("Invalid PaymentSimulationRequested", e);
        }
        Mdc.fromConsumer(record, env);
        try {
            // Business processing — transient failures propagate (retryable).
            service.handleRequested(env, headers.get(Headers.IDEMPOTENCY_KEY), headers.get(Headers.TRACEPARENT));
        } finally {
            Mdc.clear();
        }
    }

    private void handleCoreResponse(ConsumerRecord<String, byte[]> record) {
        EventEnvelope<CorePaymentSimulationResponsePayload> env;
        try {
            CorePaymentSimulationResponse avro = avroSerde.deserialize(Topics.CORE_RESPONSE, record.value());
            env = AvroMapper.fromAvro(avro);
            if (env.payload() == null || env.payload().simulationId() == null) {
                throw new IllegalArgumentException("missing simulationId");
            }
        } catch (AvroCodecUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception e) {
            if (isRegistryConnectivityFailure(e)) {
                throw new RegistryUnavailableException(
                        "Registry unavailable while decoding CorePaymentSimulationResponse", e);
            }
            throw new PoisonMessageException("Invalid CorePaymentSimulationResponse", e);
        }
        Mdc.fromConsumer(record, env);
        try {
            service.handleCoreResponse(env);
        } finally {
            Mdc.clear();
        }
    }

    /**
     * A connectivity failure talking to the registry surfaces as a network exception ({@link
     * java.io.IOException} or a subclass — {@code ConnectException}, {@code SocketTimeoutException},
     * {@code HttpConnectTimeoutException}, …) somewhere in the cause chain: the Apicurio client
     * wraps the failed HTTP call in one of its own {@code RuntimeException} subclasses around
     * that underlying I/O failure. A genuinely undecodable payload never reaches the network at
     * all (a malformed wire header, an unresolvable local schema mismatch), so it never has an
     * {@code IOException} anywhere in its chain — this discriminates the two without depending on
     * a specific Apicurio exception class, which would be fragile across client versions.
     */
    private static boolean isRegistryConnectivityFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            // The Apicurio client does not preserve the underlying IOException as a Java cause
            // for a connectivity failure (JdkHttpClient/ErrorHandler#parseError synthesizes a
            // fresh RestClientException from just the failing exception's class name/message,
            // discarding the original exception entirely) — its OWN "no HTTP response at all"
            // signal is an embedded Error with no errorCode (handleErrorResponse, used when the
            // registry DID answer with an HTTP error status, always sets one).
            if (cause instanceof io.apicurio.registry.rest.client.exception.RestClientException restClientException
                    && restClientException.getError() != null
                    && restClientException.getError().getErrorCode() == null) {
                return true;
            }
        }
        return false;
    }
}
