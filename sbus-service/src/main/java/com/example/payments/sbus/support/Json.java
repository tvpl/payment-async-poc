package com.example.payments.sbus.support;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;

/** Thin wrapper around Micronaut Serde's ObjectMapper for (de)serializing JSON strings. */
@Singleton
public class Json {

    private final ObjectMapper objectMapper;

    public Json(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + value, e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize to " + type, e);
        }
    }

    /** Generic-aware deserialization, e.g. {@code EventEnvelope<PaymentSimulationRequestPayload>}. */
    public <T> T fromJson(String json, Argument<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize to " + type, e);
        }
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }
}
