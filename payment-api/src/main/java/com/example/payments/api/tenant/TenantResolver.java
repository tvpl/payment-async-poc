package com.example.payments.api.tenant;

import com.example.payments.api.config.SecurityProperties;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Resolves the effective tenant for a request from the {@code payment.security.tenants} binding
 * (hash of the API key -&gt; authorized tenants). The credential is always the trust anchor;
 * {@code X-Tenant-Id} only selects among the tenants that credential's binding authorizes -
 * presence outside the binding is rejected (TEN-01), never silently corrected.
 *
 * <p><strong>Unbound credential (spec-precision gap):</strong> the spec defines behavior only for
 * a binding with exactly one tenant (TEN-02) or more than one (TEN-03); it does not define a
 * credential with zero bound tenants. This resolver treats that case like a single implicit
 * {@value #UNBOUND_DEFAULT_TENANT} tenant when no header is declared - preserving today's
 * single-tenant behavior for credentials nobody has bound yet - and as {@link TenantResolution.Forbidden}
 * the moment a header is declared, since no declared tenant can be "inside" an empty binding
 * (still exactly TEN-01's rule, just applied to the empty-set case).
 */
@Singleton
public class TenantResolver {

    /** Applied only when the credential has no entry at all in {@code payment.security.tenants}. */
    static final String UNBOUND_DEFAULT_TENANT = "default";

    private final Map<String, List<String>> tenants;

    public TenantResolver(SecurityProperties properties) {
        this.tenants = properties.getTenants();
    }

    /**
     * @param apiKey       the raw (unhashed) credential from {@code X-API-Key}; hashed here, never
     *                     stored or logged in clear
     * @param tenantHeader the raw {@code X-Tenant-Id} header value, or {@code null}/blank if absent
     */
    public TenantResolution resolve(String apiKey, String tenantHeader) {
        List<String> bound = boundTenants(apiKey);
        String declared = (tenantHeader == null || tenantHeader.isBlank()) ? null : tenantHeader;

        if (bound.isEmpty()) {
            return declared == null
                    ? new TenantResolution.Effective(UNBOUND_DEFAULT_TENANT)
                    : new TenantResolution.Forbidden();
        }
        if (declared == null) {
            return bound.size() == 1
                    ? new TenantResolution.Effective(bound.get(0))
                    : new TenantResolution.MissingHeader();
        }
        return bound.contains(declared)
                ? new TenantResolution.Effective(declared)
                : new TenantResolution.Forbidden();
    }

    private List<String> boundTenants(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        return tenants.getOrDefault(sha256Hex(apiKey), List.of());
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to resolve a tenant binding", e);
        }
    }
}
