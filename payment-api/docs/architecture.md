# Arquitetura

A API é a única fronteira síncrona de um fluxo assíncrono. Roda na porta **8080** e transforma
"publique um evento e espere" em uma resposta HTTP, sem prometer mais do que observou.

## Mapa de classes

Pacote base: `com.example.payments.api`.

| Classe | Arquivo | Papel |
|---|---|---|
| `PaymentSimulationController` | `controller/PaymentSimulationController.java` | Endpoints `POST`/`GET` de simulação |
| `PaymentSimulationRequest` | `dto/PaymentSimulationRequest.java` | Corpo validado (Bean Validation) |
| `StatusResponse` / `StatusEntry` | `dto/StatusResponse.java`, `dto/StatusEntry.java` | Resposta HTTP / entrada armazenada no Redis |
| `ApiPaymentService` | `service/ApiPaymentService.java` | Orquestra submissão, consulta, idempotência e espera |
| `ResponseCoordinator` | `coordination/ResponseCoordinator.java` | Correlaciona evento de resposta → requisição em espera |
| `RedisStatusStore` | `redis/RedisStatusStore.java` | Status, resultado e reserva de idempotência no Redis |
| `PaymentRequestProducer` | `kafka/PaymentRequestProducer.java` | Publica `PaymentSimulationRequested` (Avro) |
| `PaymentResponseConsumer` | `kafka/PaymentResponseConsumer.java` | Consome `Completed`/`Failed`, aplica ou envia à DLQ |
| `ConcurrencyLimitFilter` | `filter/ConcurrencyLimitFilter.java` | Admissão (rate limit distribuído) → `429` |
| `ApiKeyFilter` | `filter/ApiKeyFilter.java` | Autenticação por `X-API-Key` → `401` |
| `SbusStatusClient` / `SbusStatusGateway` | `client/SbusStatusClient.java`, `coordination/SbusStatusGateway.java` | Cliente HTTP e circuito do fallback durável do `GET` |
| `ApiMetrics` | `metrics/ApiMetrics.java` | Métricas Micrometer |
| Handlers `problem+json` | `error/*` | `Problem`, `PublishFailedExceptionHandler`, `ValidationExceptionHandler`, `IdempotencyConflictExceptionHandler` |

## Caminho de uma submissão

```text
POST /payment-simulations
  ├─ ApiKeyFilter                 autenticação da chamada
  ├─ ConcurrencyLimitFilter       admissão por recurso e por tenant (429 quando estoura)
  └─ ApiPaymentService.submit
       ├─ IdempotencyFingerprint  fingerprint canônico do payload
       ├─ RedisStatusStore.reserve  SET NX com {requestId, fingerprint, publishState, lease}
       │    ├─ Reserved       → publica
       │    ├─ ResumePublish  → republica sob o MESMO requestId
       │    ├─ Replay         → devolve a identidade original
       │    └─ Conflict       → 409
       ├─ ResponseCoordinator.register   waiter local
       ├─ AvroSerde + PaymentRequestProducer → Kafka (acks=all)
       ├─ markPublishState(PUBLISHED)   só depois do ack
       └─ await(waitTimeout) → resultado (200/422) ou 202
```

O handler roda em `TaskExecutors.BLOCKING`, apoiado por virtual threads no JDK 21: milhares de
requisições podem esperar em paralelo sem consumir uma thread de plataforma cada.

## Caminho de uma consulta

```text
GET /payment-simulations/{requestId}
  └─ ApiPaymentService.getStatus
       ├─ RedisStatusStore   entrada presente e terminal → devolve (200)
       ├─ ausente ou não-terminal → SbusStatusGateway (fallback durável, com circuito)
       └─ desconhecido nos dois → 404
```

O Redis é a fonte rápida; o SBUS é consultado como fallback quando a entrada ainda não chegou ou não
é terminal, para que um resultado já concluído não se perca. `SbusStatusGateway` mantém um circuito
sobre o `SbusStatusClient`: uma chamada isolada já é limitada pelo timeout HTTP do cliente, mas sem o
circuito uma consulta repetida pagaria esse timeout a cada tentativa contra um SBUS fora do ar.

## Caminho de uma resposta

```text
payment.simulation.completed|failed
  └─ PaymentResponseConsumer (group payment-api, SYNC_PER_RECORD)
       ├─ decode falha        → DLQ (stage decode) com os bytes originais
       ├─ codec sem capacidade → relança, o registro é reentregue
       ├─ já terminal          → mantém o desfecho, acorda o waiter
       ├─ aplica              → Redis + waiter local + pub/sub para as demais instâncias
       └─ aplicar falha N vezes → DLQ (stage apply)
```

## Correlação entre instâncias

Uma resposta chega em **uma** instância, mas o cliente pode estar esperando em outra. Quem consome
grava o resultado no Redis e publica o `requestId` num canal pub/sub; todas as instâncias assinam e
acordam o waiter local. Um único consumer group estável evita tanto o vazamento de grupos órfãos
(um grupo aleatório por restart) quanto o processamento N× redundante de um grupo por instância.

`ResponseCoordinator` cobre três corridas nessa correlação:
- **read-after-register**: logo depois de registrar o waiter (já com a publicação em Kafka feita),
  consulta o Redis diretamente. Cobre o caso em que o resultado ficou pronto antes da inscrição no
  pub/sub ou do registro do waiter.
- **resubscribe tolerante**: se o Redis estiver fora no boot, a inscrição no canal é reagendada a
  cada 5s em vez de derrubar a instância.
- **shutdown gracioso**: no encerramento, todos os waiters pendentes são completados com falha
  controlada e a lista é limpa. A requisição cai para `202` em vez de pendurar a conexão, e nenhum
  registro fica para trás.

## Fronteiras

A API **consome** contratos publicados e **não** define esquema, tópico ou tabela. Infraestrutura é
do `/sandbox` (AD-003); o `compose.yaml` daqui sobe apenas a aplicação e se conecta à rede externa.
