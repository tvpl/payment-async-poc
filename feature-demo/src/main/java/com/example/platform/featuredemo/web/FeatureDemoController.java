package com.example.platform.featuredemo.web;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.kafka.TopicRouter;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.resolver.FeatureResolver;
import com.example.platform.featurecontrol.version.ApiVersionResolver;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;

/**
 * One endpoint per feature-control scenario. Each reads the (optional) JWT into a
 * {@link FeatureContext} and asks the shared {@link FeatureResolver} — the exact call any of the
 * 30+ apps would make. Flip any flag at runtime via {@code PUT /admin/features/{name}} and re-hit
 * these to watch decisions change within one cache-ttl.
 */
@Controller
@Secured(SecurityRule.IS_ANONYMOUS)
public class FeatureDemoController {

    private static final String V0_FLAG = "payment-api-v0";

    private final FeatureResolver resolver;
    private final ApiVersionResolver versionResolver;
    private final TopicRouter topicRouter;
    private final DemoContexts contexts;

    public FeatureDemoController(FeatureResolver resolver,
                                 ApiVersionResolver versionResolver,
                                 TopicRouter topicRouter,
                                 DemoContexts contexts) {
        this.resolver = resolver;
        this.versionResolver = versionResolver;
        this.topicRouter = topicRouter;
        this.contexts = contexts;
    }

    /** Scenario 1 — feature toggle: 100% to service A or B. */
    @Get("/demo/toggle")
    public DemoResponse toggle(@Nullable Authentication auth, @Header(value = "X-Anon-Id", defaultValue = "") String anonId) {
        FeatureContext ctx = contexts.from(auth, anonId);
        FeatureDecision d = resolver.evaluate("demo-toggle", ctx);
        return response("feature-toggle", d, ctx,
                "Toggle is " + (d.isOn() ? "ON" : "OFF") + " -> route 100% to " + d.variant());
    }

    /** Scenario 2 — A/B rollout by percentage, sticky per user/anon id. */
    @Get("/demo/ab")
    public DemoResponse ab(@Nullable Authentication auth, @Header(value = "X-Anon-Id", defaultValue = "") String anonId) {
        FeatureContext ctx = contexts.from(auth, anonId);
        FeatureDecision d = resolver.evaluate("demo-ab", ctx);
        return response("ab-rollout", d, ctx,
                "Bucketed by '" + ctx.bucketingKey() + "' -> variant " + d.variant() + " (" + d.reason() + ")");
    }

    /** Scenario 3 — restricted group / per-user (recognized from the JWT). */
    @Get("/demo/restricted")
    public HttpResponse<DemoResponse> restricted(@Nullable Authentication auth, @Header(value = "X-Anon-Id", defaultValue = "") String anonId) {
        FeatureContext ctx = contexts.from(auth, anonId);
        FeatureDecision d = resolver.evaluate("demo-restricted", ctx);
        DemoResponse body = response("restricted", d, ctx,
                d.isOn() ? "Access granted (" + d.reason() + ")" : "Access denied — not in allowlist");
        return d.isOn() ? HttpResponse.ok(body) : HttpResponse.<DemoResponse>status(io.micronaut.http.HttpStatus.FORBIDDEN).body(body);
    }

    /** Scenario 4 — API v0 versioning, feature-gated default (no explicit version). */
    @Get("/demo/version")
    public VersionResponse version(@Nullable Authentication auth,
                                   @Header(value = "X-Api-Version", defaultValue = "") String requested,
                                   @Header(value = "X-Anon-Id", defaultValue = "") String anonId) {
        FeatureContext ctx = contexts.from(auth, anonId);
        String resolved = versionResolver.resolve(V0_FLAG, ctx, blankToNull(requested), "v0", "v1");
        boolean betaAvailable = versionResolver.betaAvailable(V0_FLAG, ctx);
        return new VersionResponse(blankToNull(requested), resolved, betaAvailable, ctx.userId(),
                betaAvailable ? "Eligible for v0 (in v0-testers)" : "Not eligible — served " + resolved);
    }

    /** The frontend hitting v0 explicitly. Granted only if eligible, else transparently v1. */
    @Get("/v0/demo")
    public VersionResponse v0(@Nullable Authentication auth, @Header(value = "X-Anon-Id", defaultValue = "") String anonId) {
        FeatureContext ctx = contexts.from(auth, anonId);
        String resolved = versionResolver.resolve(V0_FLAG, ctx, "v0", "v0", "v1");
        boolean betaAvailable = versionResolver.betaAvailable(V0_FLAG, ctx);
        return new VersionResponse("v0", resolved, betaAvailable, ctx.userId(),
                betaAvailable ? "Serving v0" : "Requested v0 but not eligible — downgraded to " + resolved);
    }

    /** The stable version, always available. */
    @Get("/v1/demo")
    public VersionResponse v1(@Nullable Authentication auth) {
        FeatureContext ctx = contexts.from(auth, null);
        return new VersionResponse("v1", "v1", versionResolver.betaAvailable(V0_FLAG, ctx), ctx.userId(),
                "Serving stable v1");
    }

    /** Kafka topic A/B routing from a flag decision. */
    @Get("/demo/topic")
    public TopicResponse topic(@Nullable Authentication auth, @Header(value = "X-Anon-Id", defaultValue = "") String anonId) {
        FeatureContext ctx = contexts.from(auth, anonId);
        TopicRouter.Routed routed = topicRouter.routeWithReason(
                "checkout-topic", ctx, "checkout.topic-a", "checkout.topic-b");
        return new TopicResponse(routed.topic(), routed.decision(), ctx.userId(),
                "Would produce to " + routed.topic() + " (" + routed.decision().reason() + ")");
    }

    private DemoResponse response(String scenario, FeatureDecision d, FeatureContext ctx, String explanation) {
        return new DemoResponse(scenario, d, ctx.userId(), ctx.groups(), ctx.bucketingKey(), explanation);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Serdeable
    public record VersionResponse(@Nullable String requested, String resolved, boolean betaAvailable,
                                  @Nullable String userId, String explanation) {
    }

    @Serdeable
    public record TopicResponse(String topic, FeatureDecision decision, @Nullable String userId, String explanation) {
    }
}
