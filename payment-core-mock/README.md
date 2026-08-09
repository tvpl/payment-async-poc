# payment-core-mock — NON_PRODUCTION

Simulador determinístico da integração assíncrona de pagamento. Consome comandos Avro pelo Kafka, calcula uma resposta reproduzível a partir de `CORE_SEED` + `requestId` e publica o evento de resposta. Serve exclusivamente a desenvolvimento, testes de contrato e cenários de falha; não define SLA, regra financeira ou comportamento de uma dependência externa.

## Quickstart

Requisitos: JDK 21 e os GAVs `payment-contract-model` e `payment-contract-avro-apicurio` publicados em um repositório Maven. No workspace transitório:

```bash
cd ../payment-contracts
./gradlew publishAllToLocalBuildRepository --no-daemon
cd ../payment-core-mock
./gradlew test --no-daemon
```

Para executar com a infraestrutura local já iniciada pelo sandbox:

```bash
cp .env.example .env
docker compose --env-file .env up --build --wait core-mock
curl --fail http://localhost:8082/health
docker compose --env-file .env down
```

O Compose desta raiz não cria Kafka, Registry ou observabilidade. A rede externa `payment-sandbox` deve existir.

## Perfis determinísticos

`CORE_DECLINE_PCT` seleciona respostas recusadas, `CORE_FAIL_PCT` seleciona falhas transitórias e o restante é aprovado. A soma não pode exceder 100. `CORE_LATENCY_MIN_MS` e `CORE_LATENCY_MAX_MS` formam um intervalo inclusivo. Com o mesmo seed, request id e configuração, outcome, latência e código de autorização permanecem equivalentes, inclusive após redelivery.

Exemplos seguros:

- sucesso: `CORE_DECLINE_PCT=0`, `CORE_FAIL_PCT=0`;
- recusa: `CORE_DECLINE_PCT=100`, `CORE_FAIL_PCT=0`;
- falha transitória: `CORE_DECLINE_PCT=0`, `CORE_FAIL_PCT=100`.

## Contratos e dependências

- entrada: `payment.simulation.core.command`;
- saída: `payment.simulation.core.response`;
- contratos: GAVs versionados de `payment-contracts`, nunca source path;
- runtime: Kafka, Apicurio Registry e collector OTLP fornecidos pelo sandbox;
- build: Maven Central e o repositório Maven de contratos configurado por `PAYMENT_CONTRACTS_REPOSITORY`.

Detalhes estão em [contratos](docs/contracts.md) e [configuração](docs/configuration.md).

## Operação e gates

```bash
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
./gradlew build --no-daemon
scripts/verify-docs.sh
deploy/verify.sh
```

Os ITs e o smoke do contêiner exigem Docker. Ausência da dependência deve ser reportada como `NOT_RUN`, não como sucesso. Veja [operações](docs/operations.md), [testes](docs/testing.md) e [observabilidade](docs/observability.md).

## Fontes de verdade

1. código, configuração e testes locais;
2. artefatos publicados de `payment-contracts`;
3. Dockerfile, Compose e gates executáveis;
4. [documentação local](docs/README.md) e [ADRs](docs/adr/README.md).

## Status

`NON_PRODUCTION`. A imagem, o startup e o CI repetem esta classificação deliberadamente. Capacidade, segurança e comportamento aqui medidos são garantias do simulador, não de uma integração externa. Consulte [performance](docs/performance.md) e o [ADR de classificação](docs/adr/0001-non-production-deterministic-simulator.md).
