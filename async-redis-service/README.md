# async-redis-service

Exemplo async-to-sync de simulação de pagamento sobre Redis Streams (fila durável) e BRPOP (wakeup por requisição), sem Kafka. `POST /jobs` aceita, persiste status consultável antes de enfileirar e responde `200` (resultado já pronto) ou `202` (ainda em processamento, com polling em `GET /jobs/{id}`).

## Status de produção

A fronteira tem workers únicos e reconectáveis, liberação atômica de resultado, DLQ durável, retenção PEL-safe e os três gates de `POST /jobs` (autenticação, idempotência, admissão), container e CI. Um claim de produção exige relatório datado dos gates de release e capacidade; este README não substitui essa evidência. Retenção com trim `ACKED` fica registrada como trabalho futuro — ver [ADR-0001](docs/adr/0001-stream-retention-and-wakeup-protocol.md).

## Quickstart

Requisitos: JDK 21, Docker, sandbox saudável (`../sandbox`) com Redis em `localhost:6379`.

```bash
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
```

Para executar a aplicação isolada na rede externa do sandbox:

```bash
cp .env.example .env
docker compose --env-file .env up --build --wait async-redis
curl --fail http://localhost:8084/health/liveness
docker compose --env-file .env down
```

O Compose não cria dependências; consulte [operações](docs/operations.md) para diagnóstico.

## Dependências externas e contratos

- Redis: única dependência externa (Streams, consumer group, BRPOP, chaves de status/resultado/idempotência).
- HTTP próprio: `POST /jobs` e `GET /jobs/{id}` são o único contrato publicado — ver [contratos](docs/contracts.md).
- Sem Kafka, PostgreSQL, Apicurio Registry ou dependência de outra fronteira do workspace (`StandaloneBoundaryTest` garante isso no build).

Detalhes em [arquitetura](docs/architecture.md) e [configuração](docs/configuration.md).

## Operação e release

```bash
./gradlew build --no-daemon
scripts/verify-docs.sh
deploy/verify.sh --structural
```

O gate completo de imagem roda `deploy/verify.sh` com Docker e o sandbox ativos. Runbooks de worker, DLQ e retenção ficam em [ops/runbooks](ops/runbooks/README.md). Alertas e métricas em [observabilidade](docs/observability.md). CI gera SBOM e bloqueia vulnerabilidades HIGH/CRITICAL na imagem.

## Fontes de verdade

1. código, configuração e testes locais;
2. Dockerfile, Compose e gates locais;
3. [documentação](docs/README.md) e [ADRs](docs/adr/README.md).
