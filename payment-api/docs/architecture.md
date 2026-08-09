# Arquitetura

A API é a única fronteira síncrona de um fluxo assíncrono. Ela transforma "publique um evento e
espere" em uma resposta HTTP, sem prometer mais do que observou.

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

## Fronteiras

A API **consome** contratos publicados e **não** define esquema, tópico ou tabela. Infraestrutura é
do `/sandbox` (AD-003); o `compose.yaml` daqui sobe apenas a aplicação e se conecta à rede externa.
