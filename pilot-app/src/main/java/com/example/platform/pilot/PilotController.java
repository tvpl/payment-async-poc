package com.example.platform.pilot;

import com.example.platform.featurecontrol.annotation.FeatureGate;
import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.context.JwtFeatureContextFactory;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.resolver.FeatureResolver;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;

import java.util.Map;

/**
 * The exact usage pattern any consuming app follows.
 *
 * <ol>
 *   <li>Build a {@link FeatureContext} from the request's JWT via {@link JwtFeatureContextFactory}.</li>
 *   <li>Either branch on {@link FeatureResolver#evaluate} (imperative) …</li>
 *   <li>… or annotate the handler with {@link FeatureGate} (declarative) and let the interceptor
 *       hide it (404) when the flag is off for the caller.</li>
 * </ol>
 */
@Controller("/pilot")
@Secured(SecurityRule.IS_ANONYMOUS)
public class PilotController {

    private final FeatureResolver features;

    public PilotController(FeatureResolver features) {
        this.features = features;
    }

    /** Imperative use: pick engine A or B from an A/B flag, sticky per user. */
    @Get("/checkout")
    public Map<String, Object> checkout(@Nullable Authentication auth,
                                        @Header(value = "X-Anon-Id", defaultValue = "") String anonId) {
        FeatureContext ctx = JwtFeatureContextFactory.from(auth, anonId);
        FeatureDecision decision = features.evaluate("pilot-checkout", ctx);
        String engine = decision.isOn() ? "engine-v2" : "engine-v1";
        return Map.of("engine", engine, "variant", decision.variant(), "reason", decision.reason());
    }

    /** Declarative use: this route only exists for callers eligible for the beta flag. */
    @Get("/beta")
    @FeatureGate("pilot-beta")
    public Map<String, Object> beta() {
        return Map.of("ok", true, "feature", "pilot-beta");
    }
}
