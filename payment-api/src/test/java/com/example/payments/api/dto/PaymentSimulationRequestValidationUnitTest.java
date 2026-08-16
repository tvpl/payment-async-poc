package com.example.payments.api.dto;

import io.micronaut.context.ApplicationContext;
import io.micronaut.validation.validator.Validator;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-08: merchantId and captureMode get the same size/format discipline as the other string
 * fields on the public contract. Uses Micronaut's own bean-introspection validator (no
 * Kafka/Redis/HTTP server needed) instead of a standalone Jakarta Validation provider, since none
 * is on this boundary's classpath.
 */
class PaymentSimulationRequestValidationUnitTest {

    private static ApplicationContext context;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        context = ApplicationContext.run(Map.of(
                "micronaut.server.enabled", false,
                "kafka.enabled", false,
                "micronaut.scheduling.enabled", false));
        validator = context.getBean(Validator.class);
    }

    @AfterAll
    static void tearDown() {
        context.close();
    }

    @Test
    void acceptsTheValidBaseline() {
        assertTrue(violations(request("MERCHANT-001", "AUTHORIZE_AND_CAPTURE")).isEmpty());
    }

    @Test
    void rejectsMerchantIdOverSixtyFourCharacters() {
        assertFalse(violations(request("M".repeat(65), "AUTHORIZE_AND_CAPTURE")).isEmpty());
    }

    @Test
    void rejectsMerchantIdWithDisallowedCharacters() {
        assertFalse(violations(request("merchant with spaces", "AUTHORIZE_AND_CAPTURE")).isEmpty());
    }

    @Test
    void rejectsBlankMerchantId() {
        assertFalse(violations(request("", "AUTHORIZE_AND_CAPTURE")).isEmpty());
    }

    @Test
    void rejectsCaptureModeOutsideThePattern() {
        assertFalse(violations(request("MERCHANT-001", "authorize-and-capture!")).isEmpty());
    }

    @Test
    void rejectsBlankCaptureMode() {
        assertFalse(violations(request("MERCHANT-001", "")).isEmpty());
    }

    @Test
    void acceptsAShortCaptureModeWithinThePattern() {
        assertTrue(violations(request("MERCHANT-001", "AUTHORIZE")).isEmpty());
    }

    private static Set<ConstraintViolation<PaymentSimulationRequest>> violations(PaymentSimulationRequest request) {
        return validator.validate(request);
    }

    private static PaymentSimulationRequest request(String merchantId, String captureMode) {
        return new PaymentSimulationRequest(
                merchantId, new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, captureMode);
    }
}
