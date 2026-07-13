package com.example.payments.api.controller;

import com.example.payments.api.error.Problem;
import com.example.platform.featurecontrol.admin.FlagAdminService;
import com.example.platform.featurecontrol.model.FlagDefinition;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;

/**
 * Runtime flag control for the API. {@code PUT /admin/features/{name}} upserts a definition into
 * Redis so it takes effect across instances within one cache-ttl — e.g. add a user to the
 * {@code payment-api-v0} allowlist without a deploy. Requires a valid JWT (a real deployment would
 * additionally require an admin scope/role).
 */
@Controller("/admin/features")
@Secured(SecurityRule.IS_ANONYMOUS)
public class FeatureAdminController {

    private final FlagAdminService adminService;

    public FeatureAdminController(FlagAdminService adminService) {
        this.adminService = adminService;
    }

    @Put("/{name}")
    public MutableHttpResponse<?> upsert(@Nullable Authentication authentication,
                                         @PathVariable String name,
                                         @Body FlagDefinition body) {
        if (authentication == null) {
            return unauthorized();
        }
        FlagDefinition definition = new FlagDefinition(
                name, body.type(), body.enabled(), body.percentage(),
                body.allowedUsers(), body.allowedGroups(), body.variants(),
                body.onVariant(), body.offVariant());
        adminService.put(definition);
        return HttpResponse.ok(definition);
    }

    @Delete("/{name}")
    public MutableHttpResponse<?> delete(@Nullable Authentication authentication,
                                         @PathVariable String name) {
        if (authentication == null) {
            return unauthorized();
        }
        adminService.delete(name);
        return HttpResponse.noContent();
    }

    private static MutableHttpResponse<?> unauthorized() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(Problem.MEDIA_TYPE)
                .body(Problem.of(401, "Unauthorized", "A valid Bearer token is required"));
    }
}
