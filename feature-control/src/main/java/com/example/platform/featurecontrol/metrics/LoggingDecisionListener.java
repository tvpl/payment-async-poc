package com.example.platform.featurecontrol.metrics;

import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import com.example.platform.featurecontrol.spi.DecisionListener;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured exposure log for auditing/debugging roll-outs. Off by default (chatty on the hot path);
 * enable with {@code platform.features.log-decisions=true}. Logs the flag, chosen variant, reason and
 * the subject's bucketing key at DEBUG.
 */
@Singleton
@Requires(property = "platform.features.log-decisions", value = "true")
public class LoggingDecisionListener implements DecisionListener {

    private static final Logger LOG = LoggerFactory.getLogger("feature.decisions");

    @Override
    public void onDecision(String flag, FeatureDecision decision, FeatureContext context) {
        LOG.debug("feature decision flag={} variant={} on={} reason={} subject={}",
                flag, decision.variant(), decision.isOn(), decision.reason(), context.bucketingKey());
    }
}
