package com.example.payments.api;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(
        info = @Info(
                title = "Payment Simulation API",
                version = "1.0",
                description = "Async payment simulation: POST waits (virtual thread) for the event-driven result."))
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
