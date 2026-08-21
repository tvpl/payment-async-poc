# Contratos

Todos os tipos vêm dos artefatos publicados de `payment-contracts`. Esta fronteira consome; não
edita esquema, tópico nem enum de contrato.

## Tópicos Kafka

| Tópico | Direção | Chave | Conteúdo |
| --- | --- | --- | --- |
| `payment.simulation.requested` | produz | `requestId` | `PaymentSimulationRequested` (Avro) |
| `payment.simulation.completed` | consome | `requestId` | `PaymentSimulationCompleted` (Avro) |
| `payment.simulation.failed` | consome | `requestId` | `PaymentSimulationFailed` (Avro) |
| `payment.simulation.dlq` | produz | chave original | bytes originais do evento não aplicável |

A chave é sempre o `requestId`, o que preserva ordem por simulação dentro da partição.

## Headers

| Header | Uso |
| --- | --- |
| `x-request-id` | identidade da simulação |
| `x-correlation-id` | correlação da tentativa |
| `Idempotency-Key` | chave de deduplicação fornecida pelo cliente - **obrigatória** em toda requisição HTTP que a exija (ver [HTTP](#http)); `[A-Za-z0-9_-]{1,128}`, `400` se ausente ou fora do padrão |
| `traceparent` | contexto W3C, injetado pela instrumentação OpenTelemetry do Kafka |
| `x-dlq-origin-topic` | tópico de onde o registro veio |
| `x-dlq-stage` | `decode` ou `apply` |
| `x-dlq-reason` | classe e mensagem da falha |

## Tenant (`X-Tenant-Id`)

Toda credencial (`X-API-Key`) está vinculada a um conjunto de tenants autorizados
(`payment.security.tenants`, hash da chave → lista de tenants). O header
`X-Tenant-Id` resolve qual tenant é efetivo para a requisição:

| Situação | Resultado |
| --- | --- |
| Header ausente e a credencial tem exatamente 1 tenant vinculado | esse tenant é o efetivo, sem exigir o header |
| Header ausente e a credencial tem mais de 1 tenant vinculado | `400` - header obrigatório quando há ambiguidade |
| Header presente e o tenant declarado está no binding da credencial | esse tenant é o efetivo |
| Header presente e o tenant declarado **não** está no binding da credencial | `403` |

Idempotência, fingerprint, chaves de status e o `tenantId` do envelope de
eventos são sempre compostos com o tenant efetivo - nunca uma chave global
(TEN-01..07). Atrás do [gateway](../../gateway/docs/architecture.md) opcional,
o Envoy injeta `X-Tenant-Id` a partir do claim `tenant_id` do JWT validado
(sobrescrevendo qualquer valor forjado pelo cliente); o Edge revalida o header
contra o binding da credencial exatamente como sem gateway - a injeção é
defesa em profundidade, não a única barreira.

## Codec

`AvroSerde` é um pool limitado de serializadores Apicurio, com timeout de aquisição. Exaustão do
pool levanta `AvroCodecUnavailableException`, que é tratada como **falta de capacidade** (registro
reentregue), nunca como mensagem envenenada.

## HTTP

| Rota | Método | Respostas |
| --- | --- | --- |
| `/payment-simulations` | POST | `200` resultado aprovado, `202` ainda processando, `400` payload inválido, `422` resultado recusado pelo Core, `409` conflito de idempotência, `429` admissão, `503` falha de publicação ou store indisponível |
| `/payment-simulations/{requestId}` | GET | `200` status, `404` desconhecido, `503` nem Redis nem o fallback durável responderam |
| `/v0/payment-simulations` | POST, GET | Mesmos códigos da rota principal quando o chamador é elegível. `404` quando a flag `payment-api-v0` resolve off — a beta é invisível para quem não é elegível, e não `401`/`403`, que revelariam sua existência |
| `/admin/features/{name}` | PUT, DELETE | `200`/`204` aplicado, `409` conflito de CAS (`version` desatualizada), exige `ROLE_ADMIN` |
| `/health/liveness`, `/health/readiness` | GET | `200` anônimo |
| `/prometheus` | GET | `200` autenticado |

Erros usam `application/problem+json`.

**Janela de idempotência**: 24h (`payment.simulation.idempotency-ttl`), maior que
o TTL do status em cache no `POST` (15m, `payment.simulation.status-ttl`).
Enquanto a janela de 24h vigorar, a mesma `(tenant, Idempotency-Key)` com
payload diferente responde sempre `409` - mesmo além dos 15m do status em
cache. Com payload idêntico, a chave sempre resolve para o mesmo `requestId`
(nunca reprocessa); a resposta do próprio `POST` repete o status já conhecido
apenas dentro dos 15m, devolvendo `TIMEOUT` depois disso. Já o `GET`
(consulta separada) tem fallback durável no Sbus quando o Redis não tem mais
o status - ver [HTTP](#http).

O `503` do `GET` é deliberado: com o Redis fora e o fallback durável também sem resposta, a API
admite que não sabe em vez de responder `404`, que afirmaria que a requisição nunca existiu. Ver
[segurança](security.md) para a matriz completa de autenticação por rota.
