package com.example.payments.api.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SEC-06: the shipped {@code logback.xml} must default the app logger to INFO in every
 * environment and let {@code LOG_LEVEL_APP} override it without a rebuild. Runs the real
 * resource through Logback's own configurator (Logback resolves {@code ${VAR:-default}} from
 * system properties before OS environment variables, so a property stands in for the env var
 * here) rather than asserting on the file's text, so a config typo that silently breaks the
 * override would fail this test too.
 */
class LogbackLevelUnitTest {

    private static final String ENV_PROPERTY = "LOG_LEVEL_APP";

    @AfterEach
    void clearOverride() {
        System.clearProperty(ENV_PROPERTY);
    }

    @Test
    void defaultsToInfoWithoutTheOverride() throws JoranException {
        assertEquals(Level.INFO, effectiveAppLevel());
    }

    @Test
    void honorsTheOverrideWithoutARebuild() throws JoranException {
        System.setProperty(ENV_PROPERTY, "DEBUG");

        assertEquals(Level.DEBUG, effectiveAppLevel());
    }

    private static Level effectiveAppLevel() throws JoranException {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        URL resource = LogbackLevelUnitTest.class.getClassLoader().getResource("logback.xml");
        assertNotNull(resource, "logback.xml must be on the test classpath");
        configurator.doConfigure(resource);

        Logger appLogger = context.getLogger("com.example.payments");
        return appLogger.getEffectiveLevel();
    }
}
