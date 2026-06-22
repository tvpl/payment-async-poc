# 14 — Glossário

| Termo | Definição |
|---|---|
| **Síncrono-sobre-assíncrono** | A API bloqueia por um tempo curto aguardando o resultado de um processamento assíncrono; se não chega, responde `202` e o cliente consulta depois. |
| **Idempotência** | Repetir a mesma operação produz o mesmo efeito de fazê-la uma vez. Aqui via `Idempotency-Key`, `request_id` UNIQUE e `idempotency_record`. |
| **Outbox Pattern** | Gravar o evento a publicar numa tabela **na mesma transação** do estado; um publicador assíncrono envia ao broker depois. Resolve o *dual-write*. |
| **Dual-write** | Anti-padrão de escrever em dois sistemas (banco + broker) sem atomicidade — um pode falhar deixando inconsistência. |
| **DLQ (Dead Letter Queue)** | Tópico para mensagens que não podem ser processadas (poison) ou que esgotaram tentativas. Aqui `payment.simulation.dlq`. |
| **Backpressure** | Capacidade de o sistema "empurrar de volta" a pressão de carga em vez de aceitar tudo. Aqui via Kafka (buffer) + rate limit. |
| **At-least-once** | Garantia de entrega "pelo menos uma vez" — pode haver duplicatas; por isso consumidores idempotentes. |
| **Consumer group** | Conjunto de consumidores que dividem as partições de um tópico. Grupos diferentes recebem cópias independentes. |
| **Partição / offset** | Partição é a unidade de paralelismo/ordem de um tópico; offset é a posição do consumidor nela. |
| **`FOR UPDATE SKIP LOCKED`** | Cláusula SQL que trava linhas selecionadas e **pula** as já travadas — permite vários workers consumirem a outbox sem colidir. |
| **Claim/lease** | Reivindicar linhas marcando-as `IN_PROGRESS` com timestamp; se o worker cair, o *lease* expira e elas voltam para `PENDING`. |
| **Virtual thread** | Thread leve da JVM (Project Loom). Bloquear em I/O não consome uma thread de plataforma. |
| **Envelope (de evento)** | Estrutura padrão que embrulha todo evento com metadados técnicos (ids de correlação, trace, tipo, versão). |
| **`correlationId`** | Id que amarra todos os eventos de um mesmo fluxo, do início ao fim. |
| **`causationId`** | Id do evento que **causou** o evento atual (cadeia de causalidade). |
| **`traceId` / `traceparent`** | Identificador do trace OTel / header W3C que propaga o contexto de tracing. |
| **Avro** | Formato binário com schema, usado nos eventos do Kafka. |
| **Schema Registry** | Serviço que armazena/serve schemas e valida compatibilidade (aqui Apicurio). |
| **Schema id embutido** | O id do schema vai dentro dos bytes do valor (headers off), tornando-os auto-descritivos — essencial para a outbox republicar. |
| **SBUS** | *Service Bus* — o serviço que desacopla API↔Core, persiste estado e garante publicação confiável. |
| **Rate limiter** | Componente que limita a taxa de operações (proteção do Core e admissão da API). |
| **KRaft** | Modo do Kafka sem ZooKeeper (metadados geridos pelo próprio cluster). |

## Ver também
- [01 Visão geral](01-visao-geral.md) · [08 Eventos e contratos](08-eventos-e-contratos.md)
