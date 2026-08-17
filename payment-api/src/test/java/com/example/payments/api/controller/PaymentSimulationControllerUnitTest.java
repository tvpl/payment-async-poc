package com.example.payments.api.controller;

import com.example.payments.api.config.SecurityProperties;
import com.example.payments.api.dto.PaymentSimulationRequest;
import com.example.payments.api.dto.StatusEntry;
import com.example.payments.api.error.Problem;
import com.example.payments.api.service.ApiPaymentService;
import com.example.payments.api.tenant.TenantResolver;
import com.example.payments.common.events.Headers;
import com.example.payments.common.model.SimulationStatus;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * IDEM-01/IDEM-02: the {@code Idempotency-Key} header is rejected before any domain I/O, proven
 * here with a mocked {@link ApiPaymentService} (no embedded server, no Redis/Kafka needed). A
 * single-tenant binding keeps the tenant check (TEN-01/02/03, tested on its own in
 * {@code TenantResolverUnitTest}) out of the way of these idempotency-focused cases.
 */
class PaymentSimulationControllerUnitTest {

    private static final String API_KEY = "test-api-key";

    private final PaymentSimulationRequest request = new PaymentSimulationRequest(
            "MERCHANT-001", new BigDecimal("10.00"), "BRL", "CREDIT_CARD", "VISA", 1, "AUTHORIZE_AND_CAPTURE");

    @Test
    void missingIdempotencyKeyIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);

        HttpResponse<?> response = controller(service).create(request, "", API_KEY, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertEquals(Problem.MEDIA_TYPE, response.getContentType().orElseThrow().toString());
        assertTrue(response.body() instanceof Problem problem && problem.status() == 400);
        verifyNoInteractions(service);
    }

    @Test
    void nullIdempotencyKeyIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);

        HttpResponse<?> response = controller(service).create(request, null, API_KEY, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void tooLongIdempotencyKeyIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        String tooLong = "a".repeat(129);

        HttpResponse<?> response = controller(service).create(request, tooLong, API_KEY, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void idempotencyKeyWithDisallowedCharactersIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);

        HttpResponse<?> response = controller(service).create(request, "not a valid key!", API_KEY, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        verifyNoInteractions(service);
    }

    @Test
    void validIdempotencyKeyReachesTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        StatusEntry entry = new StatusEntry("req-1", SimulationStatus.COMPLETED, null);
        when(service.submit(any(), anyString(), anyString(), any()))
                .thenReturn(new ApiPaymentService.SubmitResult(entry, false, false, "corr-1"));

        HttpResponse<?> response = controller(service).create(request, "valid-key-1", API_KEY, null, null);

        assertEquals(HttpStatus.OK, response.getStatus());
    }

    /** TEN-01: a declared X-Tenant-Id outside the credential's binding is forbidden, no domain I/O. */
    @Test
    void tenantOutsideTheBindingIsForbiddenWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);

        HttpResponse<?> response = controller(service).create(request, "valid-key-1", API_KEY, "not-bound-tenant", null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatus());
        assertEquals(Problem.MEDIA_TYPE, response.getContentType().orElseThrow().toString());
        assertTrue(response.body() instanceof Problem problem && problem.status() == 403);
        verifyNoInteractions(service);
    }

    /** TEN-03: an absent header with a multi-tenant binding requires the header, no domain I/O. */
    @Test
    void missingTenantHeaderWithAMultiTenantBindingIsRejectedWithoutTouchingTheService() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        SecurityProperties properties = new SecurityProperties();
        properties.setTenants(Map.of(hash(API_KEY), List.of("tenant-a", "tenant-b")));
        PaymentSimulationController controller =
                new PaymentSimulationController(service, new TenantResolver(properties));

        HttpResponse<?> response = controller.create(request, "valid-key-1", API_KEY, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatus());
        assertTrue(response.body() instanceof Problem problem && problem.status() == 400);
        verifyNoInteractions(service);
    }

    /** TEN-02: an absent header with exactly one bound tenant reaches the service with that tenant. */
    @Test
    void missingTenantHeaderWithASingleBoundTenantReachesTheServiceWithThatTenant() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        StatusEntry entry = new StatusEntry("req-1", SimulationStatus.COMPLETED, null);
        when(service.submit(any(), anyString(), org.mockito.ArgumentMatchers.eq("tenant-a"), any()))
                .thenReturn(new ApiPaymentService.SubmitResult(entry, false, false, "corr-1"));

        HttpResponse<?> response = controller(service).create(request, "valid-key-1", API_KEY, null, null);

        assertEquals(HttpStatus.OK, response.getStatus());
    }

    /** OBS-03: the response echoes back whatever correlationId the service resolved to. */
    @Test
    void successfulResponseEchoesTheResolvedCorrelationId() {
        ApiPaymentService service = mock(ApiPaymentService.class);
        StatusEntry entry = new StatusEntry("req-1", SimulationStatus.COMPLETED, null);
        when(service.submit(any(), anyString(), anyString(), any()))
                .thenReturn(new ApiPaymentService.SubmitResult(entry, false, false, "corr-echo-1"));

        HttpResponse<?> response = controller(service).create(request, "valid-key-1", API_KEY, null, "corr-echo-1");

        assertEquals("corr-echo-1", response.getHeaders().get(Headers.CORRELATION_ID));
    }

    private static PaymentSimulationController controller(ApiPaymentService service) {
        SecurityProperties properties = new SecurityProperties();
        properties.setTenants(Map.of(hash(API_KEY), List.of("tenant-a")));
        return new PaymentSimulationController(service, new TenantResolver(properties));
    }

    private static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
