# Contratos

## Eventos e tópicos

| Direção | Tópico | Evento |
| --- | --- | --- |
| entrada | `payment.simulation.core.command` | `ProcessPaymentSimulationCommand` |
| saída | `payment.simulation.core.response` | `CorePaymentSimulationResponse` |

Schemas, constantes e mapper vêm de `com.example.payments:payment-contract-model` e `com.example.payments:payment-contract-avro-apicurio`. Esta raiz não possui cópia editável de `.avsc`.

## Headers

O comando usa key e `x-request-id` iguais ao request id. A resposta preserva `x-request-id`; `traceparent` deve continuar W3C válido e manter o trace-id, podendo usar novo span-id. O envelope derivado mantém correlação e registra o evento anterior como causação.

## Outcomes simulados

- `APPROVED`: autorização de seis dígitos, fee fixa simulada e settlement derivado do comando;
- `DECLINED`: código `51` e motivo simulado;
- `TRANSIENT_FAILURE`: nenhuma resposta e offset não confirmado.

Esses valores servem a fixtures e cenários de teste. Não são política comercial nem semântica de uma dependência externa.

## Evolução

Mudança de schema, event type, tópico ou header começa em `payment-contracts`, com compatibilidade e versionamento. Este owner atualiza o consumo somente depois de publicar o GAV correspondente e prova o fluxo com Kafka e Apicurio reais.
