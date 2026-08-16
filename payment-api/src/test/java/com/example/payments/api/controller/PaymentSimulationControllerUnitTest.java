package com.example.payments.api.controller;

import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.error.Problem;
import com.example.payments.api.service.ApiPaymentService;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IDEM-01/IDEM-02: the {@code Idempotency-Key} header is rejected before any domain I/O, proven
 * here with a mocked {@link ApiPaymentService} (no embedded server, no Redis/Kafka needed).
 */
class PaymentSimulationControllerUnitTest {

    private final PaymentSimulationRequest request = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

    @Test
    void missingIdempotencyKeyIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);

        HttpResponse<?> response = new PaymentSimulationController(service).create(request, "");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertEquals(Problem.MEDIA_TYPE, response.getContentType().orElseThrow().toString());
        assertTrue(response.body() instanceof Problem problem && problem.status() == 400);
        verifyNoInteractions(service);
    }

    @Test
    void nullIdempotencyKeyIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);

        HttpResponse<?> response = new PaymentSimulationController(service).create(request, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void tooLongIdempotencyKeyIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        String tooLong = "a".repeat(129);

        HttpResponse<?> response = new PaymentSimulationController(service).create(request, tooLong);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void idempotencyKeyWithDisallowedCharactersIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);

        HttpResponse<?> response = new PaymentSimulationController(service).create(request, "not a valid key!");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void validIdempotencyKeyReachesTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        StatusEntry entry = new StatusEntry("req-1", SimulationStatus.COMPLETED, null);
        when(service.submit(any(), anyString())).thenReturn(new ApiPaymentService.SubmitResult(entry, false, false));

        HttpResponse<?> response = new PaymentSimulationController(service).create(request, "valid-key-1");

        assertEquals(HttpStatus.OK, response.getStatus());
    }
}
