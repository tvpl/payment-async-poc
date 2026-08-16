package com.example.payments.api.tenant;

/**
 * Outcome of resolving the effective tenant for a request (TEN-01/TEN-02/TEN-03). The credential
 * (API key) is always the trust anchor; {@code X-Tenant-Id} only selects among the tenants that
 * credential's binding authorizes - it never asserts a tenant on its own.
 */
public sealed interface TenantResolution {

    /** The effective tenant for this request, either selected by header or implied by the binding. */
    record Effective(String tenantId) implements TenantResolution {
    }

    /**
     * The declared {@code X-Tenant-Id} (or, with an empty binding, any implicit choice) is not
     * authorized for this credential (TEN-01).
     */
    record Forbidden() implements TenantResolution {
    }

    /**
     * {@code X-Tenant-Id} is absent and the credential's binding has more than one tenant, so the
     * caller must declare which one it means (TEN-03).
     */
    record MissingHeader() implements TenantResolution {
    }
}
