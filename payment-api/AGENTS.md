# AGENTS — payment-api

Instruções para agentes que trabalham **dentro** desta fronteira. Nada aqui descreve outra raiz.

## Mapa e ownership

| Caminho | O que vive aqui |
| --- | --- |
| `src/main/java/.../controller` | rotas HTTP e códigos de resposta |
| `src/main/java/.../service` | orquestração submit/replay/resume e espera pelo resultado |
| `src/main/java/.../idempotency` | fingerprint canônico, reserva e estado de publicação |
| `src/main/java/.../redis` | status durável e reserva atômica (`SET NX` / `SET XX KEEPTTL`) |
| `src/main/java/.../coordination` | waiters, ciclo de vida e gateway limitado do SBUS |
| `src/main/java/.../kafka` | producer de requests, consumo failure-safe e DLQ |
| `src/main/java/.../ratelimit`, `filter` | admissão por recurso e por tenant |
| `src/main/java/.../config` | propriedades tipadas e guards de startup |
| `deploy`, `ops`, `scripts`, `docs` | pacote de release, runbooks e validadores locais |

## Fontes de verdade

- Comportamento: os testes em `src/test/java` — eles são a especificação executável.
- Configuração: `application.yml` e os `@ConfigurationProperties` que a validam no startup.
- Contratos Kafka/Avro: artefatos publicados de `payment-contracts`. Esta fronteira **consome**, não define.
- Decisões: `docs/adr/`.

## Invariantes

1. A reserva de idempotência sempre carrega `requestId` + fingerprint + estado de publicação numa
   única operação atômica. Nunca grave metade da identidade.
2. `idempotency-ttl >= status-ttl`. A reserva precisa sobreviver ao status que ela protege.
3. Nada é confirmado sem estar em lugar recuperável: um evento final que não pôde ser aplicado vai
   para a DLQ com os bytes originais, ou o offset não avança.
4. Um desfecho terminal (`COMPLETED`/`FAILED`) nunca é reescrito por uma repetição.
5. O waiter termina por resultado, timeout, interrupção ou shutdown, e todos removem MDC e registro local.
6. A API nunca afirma progresso downstream que não observou. Sem status conhecido, responda
   `TIMEOUT`, jamais `PROCESSING`.
7. Com o Redis fora, a admissão degrada para a *fração* do orçamento desta instância, nunca o
   orçamento inteiro.
8. Credencial de tenant só aparece como hash em chave, log ou métrica.

## Limites de ownership e ações proibidas

- **Não** edite `payment-contracts`, `feature-control`, `payment-sbus` ou `/sandbox` a partir daqui;
  abra a mudança na fronteira dona.
- **Não** adicione valores a `SimulationStatus` nem a qualquer tipo do contrato publicado.
- **Não** declare infraestrutura (Kafka/Redis/Postgres/Registry) no `compose.yaml` desta raiz.
- **Não** use `project(':...')` nem source cross-root no build.
- **Não** versione `.env`, segredo ou chave real; `.env.example` fica sem valor atribuído.
- **Não** enfraqueça, pule ou remova teste para fazer um gate passar.
- Push, deploy, publicação externa e remoção de volumes do sandbox estão fora de autorização.

## Gates

| Gate | Comando |
| --- | --- |
| Rápido | `./gradlew test --no-daemon` |
| Integração | `API_DEV_JWT_SECRET=... PAYMENT_API_KEY=... ./gradlew test -PwithIT --no-daemon` |
| Build | `./gradlew build --no-daemon` |
| Documentação | `scripts/verify-docs.sh` |
| Pacote de release | `deploy/verify.sh --structural` (ou sem flag, com Docker e sandbox no ar) |
| Higiene | `git diff --check` e contagem de testes sem redução |

Detalhes e o que cada gate cobre: [docs/testing.md](docs/testing.md).
