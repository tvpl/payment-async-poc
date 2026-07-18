package com.example.platform.featurecontrol.resolver;

import com.example.platform.featurecontrol.bucketing.Bucketer;
import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.model.FlagDefinition;
import com.example.platform.featurecontrol.model.Variant;
import com.example.platform.featurecontrol.spi.DecisionListener;
import com.example.platform.featurecontrol.spi.FlagSource;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * The single entry point apps call to decide a feature. Given a flag name and a {@link FeatureContext},
 * it applies exactly one strategy (per {@link FlagDefinition#type()}) and returns a
 * {@link FeatureDecision} carrying the variant and an auditable {@code reason}.
 *
 * <p>Covers the four scenarios in one abstraction:
 * <ul>
 *   <li><b>Feature toggle</b> ({@code BOOLEAN}) — global on/off, i.e. route 100% to A or B.</li>
 *   <li><b>A/B rollout</b> ({@code PERCENTAGE}) — deterministic split by the context's stable
 *       bucketing key, so a user is sticky to one side (e.g. 10% on / 90% off).</li>
 *   <li><b>Restricted group / per-user</b> ({@code ALLOWLIST}) — on only for named users/groups
 *       recognized from the JWT (e.g. the v0 testers).</li>
 *   <li><b>Multivariate</b> ({@code VARIANT}) — weighted pick across named variants.</li>
 * </ul>
 *
 * <p>Before any strategy runs, the {@link MasterSwitch} (global kill-switch) is checked. On
 * {@code PERCENTAGE}/{@code VARIANT} flags, a non-empty allowlist acts as an <em>override</em> that
 * pins matching users/groups to the "on" side regardless of the percentage. An unknown or disabled
 * flag resolves "off", so a missing flag can never accidentally enable a code path. Every decision is
 * published to the registered {@link DecisionListener}s (metrics/exposure logs).
 */
@Singleton
public class FeatureResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FeatureResolver.class);

    private final FlagSource flagSource;
    private final MasterSwitch masterSwitch;
    private final List<DecisionListener> listeners;

    public FeatureResolver(FlagSource flagSource, MasterSwitch masterSwitch,
                           List<DecisionListener> listeners) {
        this.flagSource = flagSource;
        this.masterSwitch = masterSwitch;
        this.listeners = listeners;
    }

    /** Convenience: is this flag "on" for the context? */
    public boolean isEnabled(String flag, FeatureContext ctx) {
        return evaluate(flag, ctx).isOn();
    }

    /** Convenience: the resolved variant name for this flag/context. */
    public String variant(String flag, FeatureContext ctx) {
        return evaluate(flag, ctx).variant();
    }

    public FeatureDecision evaluate(String flag, FeatureContext ctx) {
        return publish(flag, ctx, decide(flag, ctx));
    }

    private FeatureDecision decide(String flag, FeatureContext ctx) {
        if (masterSwitch.isKilled()) {
            return new FeatureDecision(flag, "off", false, "kill-switch");
        }
        Optional<FlagDefinition> maybe = flagSource.find(flag);
        if (maybe.isEmpty()) {
            return new FeatureDecision(flag, "off", false, "unknown:default-off");
        }
        FlagDefinition def = maybe.get();
        if (!def.enabled()) {
            return new FeatureDecision(flag, def.offName(), false, "disabled");
        }

        return switch (def.type()) {
            case BOOLEAN -> new FeatureDecision(flag, def.onName(), true, "toggle:on");
            case ALLOWLIST -> allowlistMatch(def, ctx)
                    .map(reason -> new FeatureDecision(flag, def.onName(), true, reason))
                    .orElseGet(() -> new FeatureDecision(flag, def.offName(), false, "allowlist:excluded"));
            case PERCENTAGE -> percentage(flag, def, ctx);
            case VARIANT -> variant(flag, def, ctx);
        };
    }

    private FeatureDecision percentage(String flag, FlagDefinition def, FeatureContext ctx) {
        Optional<String> override = allowlistMatch(def, ctx);
        if (override.isPresent()) {
            return new FeatureDecision(flag, def.onName(), true, override.get() + ":override");
        }
        int bucket = Bucketer.bucket(def.effectiveSalt(), ctx.bucketingKey());
        if (bucket < def.percentage()) {
            return new FeatureDecision(flag, def.onName(), true,
                    "percentage:bucket=" + bucket + "<" + def.percentage() + "->on");
        }
        return new FeatureDecision(flag, def.offName(), false,
                "percentage:bucket=" + bucket + ">=" + def.percentage() + "->off");
    }

    private FeatureDecision variant(String flag, FlagDefinition def, FeatureContext ctx) {
        Optional<String> override = allowlistMatch(def, ctx);
        if (override.isPresent()) {
            return new FeatureDecision(flag, def.onName(), true, override.get() + ":override");
        }
        Variant chosen = Bucketer.select(def.variants(), def.effectiveSalt(), ctx.bucketingKey());
        if (chosen == null) {
            return new FeatureDecision(flag, def.offName(), false, "variant:empty->off");
        }
        return new FeatureDecision(flag, chosen.name(), true, "variant:" + chosen.name());
    }

    /** @return a reason ({@code allowlist:user}/{@code allowlist:group}) if the context matches. */
    private Optional<String> allowlistMatch(FlagDefinition def, FeatureContext ctx) {
        if (ctx.userId() != null && def.allowedUsers().contains(ctx.userId())) {
            return Optional.of("allowlist:user");
        }
        if (!def.allowedGroups().isEmpty() && ctx.inAnyGroup(def.allowedGroups())) {
            return Optional.of("allowlist:group");
        }
        return Optional.empty();
    }

    private FeatureDecision publish(String flag, FeatureContext ctx, FeatureDecision decision) {
        for (DecisionListener listener : listeners) {
            try {
                listener.onDecision(flag, decision, ctx);
            } catch (Exception e) {
                LOG.debug("decision listener {} failed: {}", listener.getClass().getSimpleName(), e.getMessage());
            }
        }
        return decision;
    }
}
