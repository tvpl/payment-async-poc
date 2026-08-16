package com.example.payments.api.tenant;

import com.example.payments.api.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit coverage 1:1 with the P1 story ACs 3-5 (TEN-01/TEN-02/TEN-03): a declared tenant outside
 * the credential's binding is forbidden, an absent header falls back to the binding's single
 * tenant, and an absent header with a multi-tenant binding demands the header.
 */
class TenantResolverUnitTest {

    private static final String SINGLE_TENANT_KEY = "single-tenant-key";
    private static final String MULTI_TENANT_KEY = "multi-tenant-key";
    private static final String UNBOUND_KEY = "unbound-key";

    private final TenantResolver resolver = resolverWith(Map.of(
            hash(SINGLE_TENANT_KEY), List.of("tenant-a"),
            hash(MULTI_TENANT_KEY), List.of("tenant-b", "tenant-c")));

    /** TEN-01: a declared X-Tenant-Id outside the credential's binding is forbidden. */
    @Test
    void declaredTenantOutsideTheBindingIsForbidden() {
        TenantResolution resolution = resolver.resolve(SINGLE_TENANT_KEY, "tenant-not-bound");

        assertInstanceOf(TenantResolution.Forbidden.class, resolution);
    }

    /** TEN-01: forbidden even when the declared tenant belongs to a different credential's binding. */
    @Test
    void declaredTenantBoundToAnotherCredentialIsForbidden() {
        TenantResolution resolution = resolver.resolve(SINGLE_TENANT_KEY, "tenant-b");

        assertInstanceOf(TenantResolution.Forbidden.class, resolution);
    }

    /** TEN-02: header absent, binding has exactly one tenant -> that tenant is effective. */
    @Test
    void absentHeaderWithASingleBoundTenantUsesThatTenant() {
        TenantResolution resolution = resolver.resolve(SINGLE_TENANT_KEY, null);

        assertEquals(new TenantResolution.Effective("tenant-a"), resolution);
    }

    @Test
    void blankHeaderIsTreatedAsAbsent() {
        TenantResolution resolution = resolver.resolve(SINGLE_TENANT_KEY, "  ");

        assertEquals(new TenantResolution.Effective("tenant-a"), resolution);
    }

    /** TEN-03: header absent, binding has more than one tenant -> the header is required (400). */
    @Test
    void absentHeaderWithMultipleBoundTenantsRequiresTheHeader() {
        TenantResolution resolution = resolver.resolve(MULTI_TENANT_KEY, null);

        assertInstanceOf(TenantResolution.MissingHeader.class, resolution);
    }

    /** A declared tenant matching one of several bound tenants is accepted. */
    @Test
    void declaredTenantMatchingOneOfSeveralBoundTenantsIsEffective() {
        TenantResolution resolution = resolver.resolve(MULTI_TENANT_KEY, "tenant-c");

        assertEquals(new TenantResolution.Effective("tenant-c"), resolution);
    }

    /**
     * Documented resolver contract for a credential with no binding entry at all: implicit single
     * default tenant when no header is declared (see {@link TenantResolver} javadoc).
     */
    @Test
    void unboundCredentialWithNoHeaderUsesTheImplicitDefaultTenant() {
        TenantResolution resolution = resolver.resolve(UNBOUND_KEY, null);

        assertEquals(new TenantResolution.Effective(TenantResolver.UNBOUND_DEFAULT_TENANT), resolution);
    }

    /** TEN-01 applied to the empty-binding case: any declared tenant is outside an empty set. */
    @Test
    void unboundCredentialWithADeclaredHeaderIsForbidden() {
        TenantResolution resolution = resolver.resolve(UNBOUND_KEY, "any-tenant");

        assertInstanceOf(TenantResolution.Forbidden.class, resolution);
    }

    @Test
    void missingCredentialIsForbidden() {
        TenantResolution resolution = resolver.resolve(null, "tenant-a");

        assertInstanceOf(TenantResolution.Forbidden.class, resolution);
    }

    private static TenantResolver resolverWith(Map<String, List<String>> tenants) {
        SecurityProperties properties = new SecurityProperties();
        properties.setTenants(tenants);
        return new TenantResolver(properties);
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
