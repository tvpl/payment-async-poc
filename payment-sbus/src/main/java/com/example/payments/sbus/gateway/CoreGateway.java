package com.example.payments.sbus.gateway;

/**
 * Abstraction over the (external/future) Core payment engine.
 *
 * <p>In this PoC the Core is reached <strong>asynchronously via Kafka</strong>: the
 * SBUS records a {@code ProcessPaymentSimulationCommand} in its outbox and the
 * {@code OutboxPublisher} delivers it to {@code payment.simulation.core.command};
 * the Core (here, {@code core-mock}) replies on {@code payment.simulation.core.response}.
 *
 * <p>This interface exists to make the boundary explicit and swappable. A future
 * implementation could instead call a legacy Core over HTTP/gRPC — the rest of the
 * SBUS (outbox, persistence, idempotency) would not change. The default path
 * therefore needs no concrete bean: it is the outbox topic itself.
 */
public interface CoreGateway {

    /** Marker describing how this gateway reaches the Core, for observability/docs. */
    String description();
}
