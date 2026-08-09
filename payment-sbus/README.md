# payment-sbus

Coordenador assíncrono durável do fluxo de simulação de pagamento. Consome solicitações e respostas do Core, persiste estado e outbox na mesma transação, publica retries no instante devido e mantém DLQ recuperável até o ack do broker.

## Status de produção

A fronteira possui controles de segurança, durabilidade, retenção, container e CI. Um claim de produção exige relatório datado dos gates de release e capacidade; este README não substitui essa evidência.

## Quickstart

Requisitos: JDK 21, Docker, sandbox saudável e os GAVs de `payment-contracts` publicados.

```bash
cd ../payment-contracts
./gradlew publishAllToLocalBuildRepository --no-daemon
cd ../payment-sbus
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
```

Para executar a aplicação isolada na rede externa do sandbox:

```bash
cp .env.example .env
# preencha POSTGRES_PASSWORD no .env local
docker compose --env-file .env up --build --wait sbus
curl --fail http://localhost:8081/health/liveness
docker compose --env-file .env down
```

O Compose não cria dependências. Consulte [operações](docs/operations.md) para diagnóstico e recovery.

## Dependências externas e contratos

- Kafka: tópicos versionados de request, core command/response, terminal, retry e DLQ;
- PostgreSQL: estado, idempotência e outbox com migrations append-only;
- Redis: rate limit distribuído do Core, com falha fechada;
- Apicurio Registry: resolução de schemas Avro publicados;
- contratos Java/Avro: GAVs versionados de `payment-contracts`, sem source dependency.

Os detalhes ficam em [arquitetura](docs/architecture.md), [contratos](docs/contracts.md) e [configuração](docs/configuration.md).

## Operação e release

```bash
./gradlew build --no-daemon
scripts/verify-docs.sh
deploy/verify.sh --structural
```

O gate completo de imagem roda `deploy/verify.sh` com o sandbox ativo. Retry/DLQ e rollback estão nos [runbooks](ops/runbooks/README.md). Alertas e métricas ficam em [observabilidade](docs/observability.md). CI gera SBOM e bloqueia vulnerabilidades HIGH/CRITICAL na imagem.

## Fontes de verdade

1. código, configuração, migrations e testes locais;
2. artefatos publicados de contratos;
3. Dockerfile, Compose e gates locais;
4. [documentação](docs/README.md) e [ADRs](docs/adr/README.md).
