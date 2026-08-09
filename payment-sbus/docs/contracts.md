# Contratos

| Direção | Tópicos | Responsabilidade |
| --- | --- | --- |
| entrada | `payment.simulation.requested`, `payment.simulation.core.response` | iniciar e finalizar simulação |
| saída | `payment.simulation.core.command`, `payment.simulation.completed`, `payment.simulation.failed` | comando e terminais |
| recuperação | tópicos `.retry` e `payment.simulation.dlq` | retry devido e falha recuperável |

Key Kafka, `requestId`, `correlationId`, `causationId`, `traceparent`, event type e bytes Avro são preservados nos caminhos de retry/DLQ. Schemas e modelos pertencem aos GAVs `payment-contract-model` e `payment-contract-avro-apicurio`.

O endpoint `/internal/simulations/{requestId}` exige `ROLE_PAYMENT_API`. Liveness/readiness são públicos e mínimos; health agregado e Prometheus exigem autenticação.
