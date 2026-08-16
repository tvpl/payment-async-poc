# Evidências de produção

Resumo dos gaps de hardening já tratados no código, as garantias que resultam disso, e o que continua sendo responsabilidade de deploy/operação. Evidência específica de uma fronteira (métricas, gate de teste, resultado de smoke) vive no `testing.md`/`operations.md` daquela fronteira; este documento é a visão consolidada cross-boundary.

## Gaps corrigidos no código

| Gap | Risco | Correção | Fronteira |
|---|---|---|---|
| Serialização Avro dentro da transação | Conexão de banco presa em I/O de registry até o pool esgotar | Serializar fora da TX; só escrita dentro da TX | `payment-sbus` |
| Consumer group da API instável entre restarts | Grupos órfãos infinitos e processamento N vezes redundante | Grupo estável (`payment-api`); fanout via Redis pub/sub | `payment-api` |
| Serializador Avro com lock global (`synchronized`) | Teto de throughput por lock único | Instâncias `ThreadLocal` (sem contenção) | `payment-contracts` |
| Crescimento ilimitado de tabelas | Volumetria e disco | Retenção/housekeeping de `idempotency_record` e `payment_sbus_message` terminais | `payment-sbus` |
| Perda em falha de DLQ | Mensagem some se DLQ ou broker cai | Rethrow com estratégia que não avança o offset em erro | `payment-api`, `payment-sbus` |
| Rate limiter por instância | Limite global vira N vezes o configurado | Rate limiter distribuído via Redis (Core e admissão da API) | `payment-sbus`, `payment-api` |
| Producer não idempotente | Duplicatas em retry do producer | `acks=all` + `enable.idempotence=true` | `payment-api`, `payment-sbus` |
| Marks da outbox em N transações | Sobrecarga de commits | Marcação em lote (`UPDATE` único) | `payment-sbus` |
| Latência de polling da outbox | Latência fim a fim | Poll-interval reduzido (documentado LISTEN/NOTIFY como evolução) | `payment-sbus` |
| Retry bloqueando a partição principal | Stall sob falha transitória | Retry topics dedicados mais DLQ ao esgotar tentativas | `payment-sbus` |
| Sem autenticação | Endpoint aberto | API key (`X-API-Key`) retorna `401` quando ausente/inválida | `payment-api` |

## Garantias resultantes

- Sem perda silenciosa: toda mensagem é processada, vai para retry, ou vai para a DLQ. O offset só avança após sucesso ou roteamento durável.
- Sem dual-write: estado e outbox no mesmo commit; publicação acontece fora da transação, via claim/lease.
- Idempotência em três camadas, cobrindo `payment-api` e `payment-sbus`; redeliveries e replays não duplicam efeito.
- Limites globais de taxa (proteção do Core e admissão da API), válidos com múltiplas instâncias de cada fronteira.
- Tabelas limitadas por retenção; outbox limitada por housekeeping.

## Responsabilidade de deploy/operação (checklist)

- [ ] Kafka multi-broker: fator de replicação 3, `min.insync.replicas=2`, eleição de líder não limpa desabilitada. Exemplo ilustrativo em [`sandbox/deploy/docker-compose.kafka-cluster.example.yml`](../sandbox/deploy/docker-compose.kafka-cluster.example.yml), mantido pela fronteira `sandbox` (não testado neste repositório como cluster real).
- [ ] TLS: terminar em gateway/service mesh (recomendado) ou habilitar TLS na aplicação e SASL/SSL no Kafka. Hoje os listeners locais são texto plano, apenas para desenvolvimento.
- [ ] AuthN/AuthZ: trocar a API key por JWT/OAuth2 mais mTLS entre fronteiras. A API key é um exemplo funcional, não o alvo de produção.
- [ ] Segredos: senhas de Postgres/Redis e chaves de API via secret manager, nunca em YAML versionado.
- [ ] Dimensionamento: ajustar pool de conexões, concorrência dos consumers e limite do rate limiter à capacidade real do Core.
- [ ] Schema Registry: definir regra de compatibilidade (por exemplo `BACKWARD`) e testes de contrato, ver [payment-contracts/docs/contracts.md](../payment-contracts/docs/contracts.md).
- [ ] Alta disponibilidade: no mínimo duas instâncias de cada fronteira; Redis e Postgres com réplica/failover.
- [ ] Observabilidade: alertas conectados ao Alertmanager; reter traces/métricas conforme política de cada fronteira.
- [ ] Retenção: revisar a configuração de housekeeping do `payment-sbus` conforme requisitos regulatórios.
- [ ] DR/backup: backup do Postgres (fonte durável) e plano de reprocessamento via DLQ.

## Redis HA (Sentinel/Cluster)

O store de flags (`feature-control`) e a fila do `async-redis-service` usam o mesmo tipo de cliente Redis injetado, então alta disponibilidade é configuração, sem mudança de código: basta sobrepor a URI de conexão por variável de ambiente.

```bash
REDIS_URI=redis-sentinel://redis-sentinel-1:26379,redis-sentinel-2:26379,redis-sentinel-3:26379/mymaster
```

A expectativa é que o cliente descubra o master via Sentinels e faça failover automático, mantendo pool de comandos bloqueantes, Streams/consumer groups e pub/sub de propagação. **Isso não é evidência: nenhuma fronteira exercita Sentinel ou Cluster em teste** — `async-redis-service/docs/architecture.md` registra o Redis single-node como ponto único de falha, e `feature-control/docs/adoption.md` recusa explicitamente prometer suporte HA sem teste. Exemplo ilustrativo (master, réplica e três Sentinels) em [`sandbox/deploy/docker-compose.redis-ha.example.yml`](../sandbox/deploy/docker-compose.redis-ha.example.yml), mantido pela fronteira `sandbox`. Para escala muito alta, Redis Cluster é a alternativa: como as chaves são por-request ou por-flag, não há operação multi-chave cross-slot.

## Itens implementados mas verificáveis só com runtime

Retry topics, limiter distribuído (Redis), multi-broker e TLS são código/configuração presentes, mas a validação funcional completa exige Docker/cluster fora do ambiente de desenvolvimento local. Localmente foram validados por compilação, testes unitários e boot do grafo de dependências de cada fronteira.

## Ver também
- [Contratos de resiliência](resilience-contracts.md) · [Política de testes](testing-policy.md) · [payment-sbus/docs/operations.md](../payment-sbus/docs/operations.md)
