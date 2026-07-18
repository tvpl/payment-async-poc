package com.example.platform.featuredemo.web;

import com.example.platform.featurecontrol.admin.AuditService;
import com.example.platform.featurecontrol.admin.FlagAdminService;
import com.example.platform.featurecontrol.admin.FlagConflictException;
import com.example.platform.featurecontrol.model.FlagDefinition;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.security.authentication.Authentication;

/**
 * Runtime flag control. {@code PUT /admin/features/{name}} upserts a definition into Redis (optimistic
 * CAS on {@code version}) so it takes effect across instances within one cache-ttl (near-instant via
 * the change channel) — no redeploy. Access requires {@code ROLE_ADMIN} (enforced by the security
 * intercept-url-map), and every change is audited.
 */
@Controller("/admin/features")
public class FeatureAdminController {

    private final FlagAdminService adminService;
    private final AuditService audit;

    public FeatureAdminController(FlagAdminService adminService, AuditService audit) {
        this.adminService = adminService;
        this.audit = audit;
    }

    @Put("/{name}")
    public MutableHttpResponse<?> upsert(@Nullable Authentication authentication,
                                         @PathVariable String name,
                                         @Body FlagDefinition body) {
        // The path is the source of truth for the flag name; version/salt come from the body (CAS).
        FlagDefinition definition = new FlagDefinition(
                name, body.type(), body.enabled(), body.percentage(),
                body.allowedUsers(), body.allowedGroups(), body.variants(),
                body.onVariant(), body.offVariant(), body.version(), body.bucketingSalt());
        try {
            FlagDefinition saved = adminService.put(definition);
            audit.record(actor(authentication), name, "upsert",
                    "enabled=" + saved.enabled() + " version=" + saved.version());
            return HttpResponse.ok(saved);
        } catch (FlagConflictException e) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @Delete("/{name}")
    public HttpResponse<Void> delete(@Nullable Authentication authentication, @PathVariable String name) {
        adminService.delete(name);
        audit.record(actor(authentication), name, "delete", null);
        return HttpResponse.noContent();
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "anonymous" : authentication.getName();
    }
}
