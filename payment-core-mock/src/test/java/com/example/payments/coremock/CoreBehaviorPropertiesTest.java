package com.example.payments.coremock;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreBehaviorPropertiesTest {

    @Test
    void acceptsDefaults() {
        assertDoesNotThrow(new CoreBehaviorProperties()::validate);
    }

    @Test
    void acceptsPercentagesThatUseTheWholeBucket() {
        CoreBehaviorProperties properties = validProperties();
        properties.setDeclinePct(60);
        properties.setFailPct(40);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void rejectsNegativeMinimumLatency() {
        CoreBehaviorProperties properties = validProperties();
        properties.setLatencyMinMs(-1);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsNegativeMaximumLatency() {
        CoreBehaviorProperties properties = validProperties();
        properties.setLatencyMaxMs(-1);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsMinimumLatencyAboveMaximumLatency() {
        CoreBehaviorProperties properties = validProperties();
        properties.setLatencyMinMs(301);
        properties.setLatencyMaxMs(300);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsNegativeDeclinePercentage() {
        CoreBehaviorProperties properties = validProperties();
        properties.setDeclinePct(-1);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsDeclinePercentageAboveOneHundred() {
        CoreBehaviorProperties properties = validProperties();
        properties.setDeclinePct(101);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsNegativeFailurePercentage() {
        CoreBehaviorProperties properties = validProperties();
        properties.setFailPct(-1);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsFailurePercentageAboveOneHundred() {
        CoreBehaviorProperties properties = validProperties();
        properties.setFailPct(101);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsCombinedPercentagesAboveOneHundred() {
        CoreBehaviorProperties properties = validProperties();
        properties.setDeclinePct(60);
        properties.setFailPct(41);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void wiresValidationIntoBeanStartup() throws NoSuchMethodException {
        assertNotNull(CoreBehaviorProperties.class
                .getDeclaredMethod("validate")
                .getAnnotation(PostConstruct.class));
    }

    private static CoreBehaviorProperties validProperties() {
        CoreBehaviorProperties properties = new CoreBehaviorProperties();
        properties.setLatencyMinMs(50);
        properties.setLatencyMaxMs(300);
        properties.setDeclinePct(10);
        properties.setFailPct(0);
        return properties;
    }
}
