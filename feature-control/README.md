# feature-control

Biblioteca compartilhada de feature flags (toggle/A-B/allowlist/variant) com JWT, cache local e store dinâmico em Redis, publicada como `com.example.platform:feature-control`. Dois exemplos `NON_PRODUCTION` (`feature-demo`, `pilot-app`) demonstram adoção; nenhum dos dois é publicado nem tem gate produtivo próprio.

## Status de produção

A biblioteca tem validação de definição de flag (FTR-01), cache last-known-good com fail-closed/stale bound (FTR-02), pubsub com reconexão e convergência medida (FTR-03), mutações CAS auditadas atomicamente (FTR-04), telemetria com cardinalidade e PII bounded (FTR-05) e um consumer fixture que certifica publicação e compatibilidade binária (FTR-06). `feature-demo`/`pilot-app` recusam inicialização sob o profile `prod` (AD-005; ver [ADR-0001](docs/adr/0001-nonproduction-example-startup-guard.md)) — não têm nem podem ter status de produção. Um claim de prontidão da biblioteca exige relatório datado do gate completo; este README não substitui essa evidência.

## Quickstart

Requisitos: JDK 21, Redis local em `localhost:6379` (para os testes `-PwithIT`).

```bash
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
```

Para rodar um exemplo isoladamente:

```bash
JWT_SIGNATURE_SECRET=<segredo-dev-32-bytes> ./gradlew :feature-demo:run --no-daemon
curl http://localhost:8083/health
```

## Dependências externas e contratos

- Redis: única dependência externa em runtime (store dinâmico de flags, pubsub de invalidação, stream de auditoria).
- Nenhuma dependência de outra fronteira do workspace — `StandaloneBoundaryTest` garante isso no build (sem `import`/`project(...)` cruzando limites).
- Contrato publicado: o GAV Maven `com.example.platform:feature-control:0.1.0` (POM + jar + sources + javadoc) e sua superfície pública de API, certificados por [`consumer-fixture`](consumer-fixture) — ver [contratos e compatibilidade](docs/configuration.md#publicação-e-consumer-fixture).

Detalhes em [arquitetura](docs/architecture.md) e [configuração](docs/configuration.md).

## Operação e release

```bash
./gradlew build -PwithIT --no-daemon
scripts/verify-docs.sh
bash scripts/verify-consumer-fixture.sh
```

`scripts/verify-consumer-fixture.sh` publica a biblioteca localmente, valida POM/jar/sources/Javadoc, roda o diff de superfície de API pública contra a baseline commitada e roda o fixture contra o artefato real (mais um check negativo: sem repositório publicado, o fixture falha ao compilar). Ver [testes](docs/testing.md).

## Fontes de verdade

1. código, configuração e testes locais;
2. `build.gradle`/`settings.gradle` (raiz e de cada subprojeto) e os gates locais deste README;
3. [documentação](docs/README.md) e [ADRs](docs/adr/README.md).
