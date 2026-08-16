# Glossário

Termos usados nos sete raízes e na documentação cross-boundary do workspace.

| Termo | Definição |
|---|---|
| **Síncrono-sobre-assíncrono** | A API bloqueia por um tempo curto aguardando o resultado de um processamento assíncrono. Se não chega, responde `202` e o cliente consulta depois. |
| **Idempotência** | Repetir a mesma operação produz o mesmo efeito de fazê-la uma vez. Via `Idempotency-Key`, `request_id` UNIQUE e `idempotency_record`. |
| **Outbox Pattern** | Grava o evento a publicar numa tabela na mesma transação do estado. Um publicador assíncrono envia ao broker depois. Resolve o dual-write. |
| **Dual-write** | Anti-padrão de escrever em dois sistemas (banco e broker) sem atomicidade. Um pode falhar deixando inconsistência. |
| **DLQ (Dead Letter Queue)** | Tópico para mensagens que não podem ser processadas (poison) ou que esgotaram tentativas. |
| **Backpressure** | Capacidade de o sistema empurrar de volta a pressão de carga em vez de aceitar tudo. Via Kafka (buffer) e rate limit. |
| **At-least-once** | Garantia de entrega pelo menos uma vez. Pode haver duplicatas, por isso os consumidores são idempotentes. |
| **Consumer group** | Conjunto de consumidores que dividem as partições de um tópico. Grupos diferentes recebem cópias independentes. |
| **Partição / offset** | Partição é a unidade de paralelismo e ordem de um tópico. Offset é a posição do consumidor nela. |
| **`FOR UPDATE SKIP LOCKED`** | Cláusula SQL que trava linhas selecionadas e pula as já travadas. Permite vários workers consumirem a mesma fila de trabalho sem colidir. |
| **Claim/lease** | Reivindicar linhas marcando-as `IN_PROGRESS` com timestamp. Se o worker cair, o lease expira e elas voltam para `PENDING`. |
| **Virtual thread** | Thread leve da JVM (Project Loom). Bloquear em I/O não consome uma thread de plataforma. |
| **Envelope (de evento)** | Estrutura padrão que embrulha todo evento com metadados técnicos: ids de correlação, trace, tipo, versão. |
| **`correlationId`** | Id que amarra todos os eventos de um mesmo fluxo, do início ao fim. |
| **`causationId`** | Id do evento que causou o evento atual (cadeia de causalidade). |
| **`traceId` / `traceparent`** | Identificador do trace OTel e header W3C que propaga o contexto de tracing. |
| **Avro** | Formato binário com schema, usado nos eventos do Kafka. |
| **Schema Registry** | Serviço que armazena e serve schemas, e valida compatibilidade. |
| **Schema id embutido** | O id do schema vai dentro dos bytes do valor (headers off), tornando-os auto-descritivos. Essencial para a outbox republicar. |
| **SBUS** | Service Bus. A fronteira que desacopla API e Core, persiste estado e garante publicação confiável. Hoje `payment-sbus`. |
| **Rate limiter** | Componente que limita a taxa de operações: proteção do Core e admissão da API. |
| **KRaft** | Modo do Kafka sem ZooKeeper, com metadados geridos pelo próprio cluster. |

## Ver também
- [Visão geral do workspace](workspace-overview.md) · [Contratos de evento (payment-contracts)](../payment-contracts/docs/contracts.md)
