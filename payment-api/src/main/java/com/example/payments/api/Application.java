package com.example.payments.api;

import com.example.payments.common.model.Fees;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import io.micronaut.runtime.Micronaut;
import io.micronaut.serde.annotation.SerdeImport;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(
        info = @Info(
                title = "Payment Simulation API",
                version = "1.0",
                description = "Async payment simulation: POST waits (virtual thread) for the event-driven result."))
// payment-contracts is deliberately framework-free (no Micronaut annotations); import
// its result types here so Serde can (de)serialize them nested in StatusResponse/StatusEntry.
@SerdeImport(SimulationResult.class)
@SerdeImport(Fees.class)
@SerdeImport(Settlement.class)
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
