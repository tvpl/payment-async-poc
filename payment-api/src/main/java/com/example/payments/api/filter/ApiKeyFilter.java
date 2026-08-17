package com.example.payments.api.filter;

import com.example.payments.api.config.SecurityProperties;
import com.example.payments.api.error.Problem;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Authenticates business endpoints with an {@code X-API-Key} header (a simple, concrete
 * mechanism for the PoC). Management endpoints (health/metrics/swagger) are not covered by
 * this filter's path pattern. Production should use JWT/OAuth2 + mTLS.
 *
 * <p>Runs on {@link TaskExecutors#BLOCKING} (BUDG-03): filter methods execute on the Netty
 * event loop by default, and this method is the admission gate ahead of the rate limiter's
 * Redis calls - it must never share that fate, even though today's check itself is in-memory.
 *
 * <p>SEC-04: every configured key - whether given as a raw dev secret or as {@code sha256:<hex>}
 * - is resolved to its SHA-256 digest at boot. The caller's credential is hashed the same way on
 * every request and compared with {@link MessageDigest#isEqual(byte[], byte[])}, so a wrong guess
 * never leaks its length of match through {@code String#equals}'s short-circuiting. Production
 * (see {@code ProductionSecurityGuard}) requires every entry to already be {@code sha256:<hex>};
 * plaintext is accepted here only so {@code dev} keeps working unchanged.
 */
@ServerFilter({"/payment-simulations", "/payment-simulations/**"})
@Order(-20) // run before the rate limiter
public class ApiKeyFilter {

    /** Config prefix marking an entry as an already-hashed key (SEC-04). */
    public static final String HASH_PREFIX = "sha256:";

    private static final String HEADER = "X-API-Key";

    private final boolean enabled;
    private final List<byte[]> apiKeyHashes;

    public ApiKeyFilter(SecurityProperties properties) {
        this.enabled = properties.isEnabled();
        this.apiKeyHashes = properties.getApiKeys().stream().map(ApiKeyFilter::targetHash).toList();
    }

    /** Returns {@code null} to proceed, or a response to short-circuit the chain. */
    @RequestFilter
    @ExecuteOn(TaskExecutors.BLOCKING)
    public @Nullable MutableHttpResponse<?> filterRequest(HttpRequest<?> request) {
        if (!enabled) {
            return null;
        }
        String key = request.getHeaders().get(HEADER);
        if (key != null && matchesAConfiguredKey(key)) {
            return null;
        }
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(Problem.MEDIA_TYPE)
                .body(Problem.of(401, "Unauthorized", "Missing or invalid " + HEADER));
    }

    private boolean matchesAConfiguredKey(String candidate) {
        byte[] candidateHash = sha256(candidate);
        boolean matched = false;
        // Every configured hash is checked, win or lose already found, so the loop's duration
        // does not itself leak which (if any) entry matched.
        for (byte[] configuredHash : apiKeyHashes) {
            if (MessageDigest.isEqual(candidateHash, configuredHash)) {
                matched = true;
            }
        }
        return matched;
    }

    private static byte[] targetHash(String configured) {
        if (configured.startsWith(HASH_PREFIX)) {
            return HexFormat.of().parseHex(configured.substring(HASH_PREFIX.length()));
        }
        return sha256(configured);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to compare API keys", e);
        }
    }
}
