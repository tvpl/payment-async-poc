package com.example.platform.featurecontrol.metrics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.platform.featurecontrol.context.FeatureContext;
import com.example.platform.featurecontrol.model.FeatureDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FTR-05: "scan de logs não encontra PII" — asserted against the real emitted log line, not the source. */
class LoggingDecisionListenerUnitTest {

    private static final String RAW_USER_ID = "alice@example.com";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger("feature.decisions");
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLog() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }

    @Test
    void theEmittedLogLineNeverContainsTheRawBucketingKey() {
        LoggingDecisionListener listener = new LoggingDecisionListener();
        FeatureContext ctx = FeatureContext.builder().userId(RAW_USER_ID).build();
        FeatureDecision decision = new FeatureDecision("demo-toggle", "service-b", true, "toggle:on");

        listener.onDecision("demo-toggle", decision, ctx);

        assertTrue(appender.list.size() >= 1, "the listener must emit a log line");
        for (ILoggingEvent event : appender.list) {
            String rendered = event.getFormattedMessage();
            assertFalse(rendered.contains(RAW_USER_ID),
                    "PII scan failed: raw subject leaked into log line: " + rendered);
        }
    }

    @Test
    void theEmittedLogLineCarriesAHashedSubjectTokenInstead() {
        LoggingDecisionListener listener = new LoggingDecisionListener();
        FeatureContext ctx = FeatureContext.builder().userId(RAW_USER_ID).build();
        FeatureDecision decision = new FeatureDecision("demo-toggle", "service-b", true, "toggle:on");

        listener.onDecision("demo-toggle", decision, ctx);

        String rendered = appender.list.get(0).getFormattedMessage();
        assertTrue(rendered.contains("subject=" + SubjectHasher.hash(RAW_USER_ID)),
                "expected the hashed token, not the raw subject, in: " + rendered);
    }
}
