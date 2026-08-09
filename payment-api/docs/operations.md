# Operação

## Pré-requisitos

- Docker em execução e a rede externa do sandbox no ar (`payment-sandbox`), owner: `/sandbox`.
- `payment-contracts` e `feature-control` publicados em repositório Maven acessível.
- `.env` preenchido a partir de `.env.example` (nunca versionado).

```bash
cp .env.example .env
docker compose --env-file .env up --build api
```

## Admissão e saturação

A rota tem dois orçamentos por janela: um da rota inteira e um por tenant. Excesso recebe `429` com
`Retry-After`, nunca enfileiramento silencioso.

Quando o Redis está indisponível, não existe mais janela compartilhada. Cada instância cai para
`limit-for-period / instances` (mínimo 1) — a *fração* do orçamento da frota, não o orçamento
inteiro. Por isso `PAYMENT_API_INSTANCES` precisa acompanhar o número real de réplicas: subestimar
esse valor permite admitir mais do que a rajada aprovada exatamente durante a falha.

Sintomas e resposta: [../ops/runbooks/admission-saturation.md](../ops/runbooks/admission-saturation.md).

## Falha de publicação

Se o Kafka recusa a publicação inicial, o cliente recebe `503` e a reserva fica marcada
`PUBLISH_FAILED`. Um retry com a mesma `Idempotency-Key` e o mesmo payload **retoma o mesmo
`requestId`**; não há identidade órfã esperando o TTL vencer. Se o processo morre no meio da
tentativa, o lease de publicação vence e o próximo retry retoma a mesma identidade.

## Eventos finais não aplicáveis

Um evento que não pode ser decodificado vai para `payment.simulation.dlq` com os bytes originais e
o motivo. Um evento válido que não pôde ser aplicado (Redis fora) é retentado dentro do orçamento e
depois vai para a DLQ com `x-dlq-stage: apply`. O offset só avança depois disso: nada é confirmado
sem estar em lugar recuperável. Procedimento: [../ops/runbooks/response-dlq.md](../ops/runbooks/response-dlq.md).

## Rollback

Reverter para a tag anterior da imagem e reiniciar; a API não possui migração de schema nem estado
local, então o rollback é imediato. O estado compartilhado (Redis) é compatível entre versões
adjacentes. Passo a passo: [../ops/runbooks/release-rollback.md](../ops/runbooks/release-rollback.md).

## Evidência de execução

| Verificação | Resultado |
| --- | --- |
| `./gradlew test -PwithIT --no-daemon` | PASS (Kafka/Redis/Apicurio reais via Testcontainers) |
| `scripts/verify-docs.sh` | PASS |
| `deploy/verify.sh --structural` | PASS |
| `deploy/verify.sh` (smoke de runtime com sandbox no ar) | `NOT_RUN` |
| Carga sustentada e spike no ambiente de referência | `NOT_RUN` — ver [performance.md](performance.md) |

`NOT_RUN` significa não executado, nunca presumido verde.
