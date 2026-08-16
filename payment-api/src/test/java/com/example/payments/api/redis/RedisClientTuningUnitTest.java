package com.example.payments.api.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** task_3801253b: every Lettuce command must time out well under the client's 60s default. */
class RedisClientTuningUnitTest {

    @Test
    void boundsTheCommandTimeoutWellUnderLettucesSixtySecondDefault() {
        RedisClient client = RedisClient.create();
        try {
            new RedisClientTuning(client);

            TimeoutOptions timeoutOptions = client.getOptions().getTimeoutOptions();
            assertTrue(timeoutOptions.isTimeoutCommands(), "per-command timeout must be enabled");
            long timeout = timeoutOptions.getSource().getTimeout(null);
            Duration configured = Duration.of(timeout, timeoutOptions.getSource().getTimeUnit().toChronoUnit());
            assertEquals(Duration.ofSeconds(2), configured);
            assertTrue(configured.compareTo(Duration.ofSeconds(60)) < 0,
                    "must be well under Lettuce's 60s default: " + configured);
        } finally {
            client.shutdown();
        }
    }
}
