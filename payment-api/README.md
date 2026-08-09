# payment-api

Entrada HTTP síncrona sobre um fluxo de pagamento assíncrono. Aceita a simulação, publica
`PaymentSimulationRequested` no Kafka, espera a resposta correlacionada dentro de um orçamento e
devolve o resultado ou `202` com a URL de status.

## Status de produção

`PRODUCTION_CANDIDATE`. A fronteira tem gates produtivos (AD-005): autenticação, idempotência com
fingerprint, recuperação de publicação, coordenação limitada e admissão com falha fechada, todos
cobertos por testes de integração contra Kafka/Redis/Apicurio reais. Nenhum claim de prontidão vale
sem relatório datado: os resultados de carga e o smoke de runtime estão em
[docs/performance.md](docs/performance.md) e [docs/operations.md](docs/operations.md), incluindo o
que ainda está `NOT_RUN`.

## Quickstart

```bash
# 1. Publique as dependências (fora desta raiz, uma vez)
(cd ../payment-contracts && ./gradlew publishAllToLocalRepository)
(cd ../feature-control && ./gradlew publishMavenPublicationToLocalBuildRepository)

# 2. Suba a infraestrutura compartilhada (owner: /sandbox)
(cd ../sandbox && docker compose up -d)

# 3. Gates locais
./gradlew test --no-daemon                 # rápido
./gradlew test -PwithIT --no-daemon        # integração (precisa de Docker)

# 4. Execute a aplicação
cp .env.example .env                       # preencha PAYMENT_API_KEY
docker compose --env-file .env up --build api
```

`POST /payment-simulations` exige o header `X-API-Key`. `Idempotency-Key` é opcional, mas sem ele
cada submissão é uma identidade nova.

## Dependências externas e contratos

| Dependência | Uso | Orçamento |
| --- | --- | --- |
| Kafka | publica `payment.simulation.requested`, consome `completed`/`failed`, DLQ | `acks=all`, DLQ em falha |
| Redis | status, reserva de idempotência, pub/sub de waiters, admissão | limitado por TTL e lease |
| Apicurio Registry | codec Avro dos contratos publicados | pool limitado com timeout de aquisição |
| payment-sbus | fallback de status durável (best-effort) | timeout de leitura + circuito |

Os contratos são consumidos como artefatos Maven publicados (`payment-contract-model`,
`payment-contract-avro-apicurio`, `feature-control`), nunca por source cross-root (AD-002).
Detalhes em [docs/contracts.md](docs/contracts.md).

## Operação e release

- Imagem non-root (`10001:10001`), filesystem read-only, sem capabilities: veja
  [docs/security.md](docs/security.md).
- Compose desta fronteira sobe **apenas** a API e conecta na rede externa do sandbox (AD-003).
- Runbooks: [ops/runbooks/README.md](ops/runbooks/README.md).
- Verificação do pacote: `deploy/verify.sh --structural` e `scripts/verify-docs.sh`.

## Fontes de verdade

- Configuração: `src/main/resources/application*.yml` e [docs/configuration.md](docs/configuration.md)
- Contratos: artefatos publicados de `payment-contracts`
- Decisões: [docs/adr/README.md](docs/adr/README.md)
- Instruções para agentes: [AGENTS.md](AGENTS.md)
- Documentação completa: [docs/README.md](docs/README.md)
