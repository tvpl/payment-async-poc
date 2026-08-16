package com.example.payments.api.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.exceptions.ConfigurationException;

import java.time.Duration;

/**
 * Boot guard for BUDG-01: {@code payment.publish-budget} must leave the caller's
 * {@code payment.simulation.wait-timeout} room to actually observe a publish failure. Without
 * this, a stalled Kafka producer could consume the whole wait budget before the caller ever sees
 * an honest 503 (BUDG-02).
 */
@Context
public final class PublishBudgetGuard {

    public PublishBudgetGuard(PublishBudgetProperties publishBudget, ApiProperties simulation) {
        validate(publishBudget.getPublishBudget(), simulation.getWaitTimeout());
    }

    static void validate(Duration publishBudget, Duration waitTimeout) {
        if (publishBudget == null || waitTimeout == null || publishBudget.compareTo(waitTimeout) >= 0) {
            throw new ConfigurationException(
                    "payment.publish-budget (" + publishBudget + ") must be strictly less than "
                            + "payment.simulation.wait-timeout (" + waitTimeout + "), otherwise a stalled "
                            + "publish can consume the entire wait budget before the caller ever gets a 503");
        }
    }
}
