package com.example.platform.featuredemo.web;

import com.example.platform.featurecontrol.admin.FlagAdminService;
import com.example.platform.featurecontrol.model.FlagDefinition;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

/**
 * Runtime flag control. {@code PUT /admin/features/{name}} upserts a definition into Redis; every
 * instance of every app picks it up within one {@code cache-ttl} — no redeploy. This is the
 * operational half of the library: ship a YAML baseline, then flip toggles, shift A/B percentages,
 * or widen the v0 allowlist live. Left anonymous for the demo; secure it (admin scope) in production.
 */
@Controller("/admin/features")
@Secured(SecurityRule.IS_ANONYMOUS)
public class FeatureAdminController {

    private final FlagAdminService adminService;

    public FeatureAdminController(FlagAdminService adminService) {
        this.adminService = adminService;
    }

    @Put("/{name}")
    public HttpResponse<FlagDefinition> upsert(@PathVariable String name, @Body FlagDefinition body) {
        // The path is the source of truth for the flag name.
        FlagDefinition definition = new FlagDefinition(
                name, body.type(), body.enabled(), body.percentage(),
                body.allowedUsers(), body.allowedGroups(), body.variants(),
                body.onVariant(), body.offVariant());
        adminService.put(definition);
        return HttpResponse.ok(definition);
    }

    @Delete("/{name}")
    public HttpResponse<Void> delete(@PathVariable String name) {
        adminService.delete(name);
        return HttpResponse.noContent();
    }
}
