package com.example.platform.featurecontrol.kafka;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.resolver.FeatureResolver;
import jakarta.inject.Singleton;

/**
 * Routes a message to Kafka topic A or B based on a feature decision — the "virar para o tópico A ou
 * B" scenario. Because the decision is deterministic per user (sticky), a given user's traffic
 * consistently lands on the same topic during an A/B migration, which keeps ordering and downstream
 * dedup sane. Works with any flag type: a BOOLEAN toggle flips 100% A↔B, a PERCENTAGE flag shifts a
 * slice, an ALLOWLIST routes only the pilot group to B.
 */
@Singleton
public class TopicRouter {

    private final FeatureResolver resolver;

    public TopicRouter(FeatureResolver resolver) {
        this.resolver = resolver;
    }

    /** @return {@code topicB} when the flag resolves "on" for the context, otherwise {@code topicA}. */
    public String route(String flag, FeatureContext ctx, String topicA, String topicB) {
        return resolver.isEnabled(flag, ctx) ? topicB : topicA;
    }

    /** Same as {@link #route} but also returns the decision (for logging/headers). */
    public Routed routeWithReason(String flag, FeatureContext ctx, String topicA, String topicB) {
        FeatureDecision decision = resolver.evaluate(flag, ctx);
        return new Routed(decision.isOn() ? topicB : topicA, decision);
    }

    public record Routed(String topic, FeatureDecision decision) {
    }
}
