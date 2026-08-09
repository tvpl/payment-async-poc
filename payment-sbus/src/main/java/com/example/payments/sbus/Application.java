package com.example.payments.sbus;

import com.example.payments.common.model.Fees;
import com.example.payments.common.model.PaymentSimulationRequestPayload;
import com.example.payments.common.model.Settlement;
import com.example.payments.common.model.SimulationResult;
import io.micronaut.runtime.Micronaut;
import io.micronaut.serde.annotation.SerdeImport;

@SerdeImport(PaymentSimulationRequestPayload.class)
@SerdeImport(SimulationResult.class)
@SerdeImport(Fees.class)
@SerdeImport(Settlement.class)
public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
