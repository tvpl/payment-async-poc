package com.example.payments.coremock;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Announces the immutable lifecycle classification whenever the HTTP server starts. */
@Singleton
final class NonProductionStartupReporter implements ApplicationEventListener<ServerStartupEvent> {

    static final String CLASSIFICATION = "NON_PRODUCTION";
    private static final Logger LOG = LoggerFactory.getLogger(NonProductionStartupReporter.class);

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        LOG.warn(
                "boundary.classification={} service=payment-core-mock purpose=deterministic-simulator",
                CLASSIFICATION);
    }
}
