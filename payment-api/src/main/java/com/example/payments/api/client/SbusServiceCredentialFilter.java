package com.example.payments.api.client;

import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.ClientFilter;
import io.micronaut.http.annotation.RequestFilter;

/**
 * Attaches the Edge's own service credential to every call the declarative {@link
 * SbusStatusClient} makes (SEC-05). Scoped to the {@code sbus} declared HTTP client only
 * ({@code serviceId}), so no other outbound call is touched.
 *
 * <p>When {@link SbusServiceTokenProvider} has no credential configured, no header is added —
 * the call goes out exactly as it did before this filter existed, and the SBUS's own security
 * (401/403) plus {@code SbusStatusGateway}'s degrade-to-empty handling take over from there.
 */
@ClientFilter(serviceId = "sbus")
public class SbusServiceCredentialFilter {

    private final SbusServiceTokenProvider tokenProvider;

    public SbusServiceCredentialFilter(SbusServiceTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @RequestFilter
    public void filterRequest(MutableHttpRequest<?> request) {
        tokenProvider.currentToken().ifPresent(request::bearerAuth);
    }
}
