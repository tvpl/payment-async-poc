# Guia local para agentes

Estas instruções governam somente `payment-sbus`. Regras cross-boundary permanecem no `AGENTS.md` do workspace.

## Mapa e ownership

- `src/main`: consumo Kafka, estado durável, outbox, retry/DLQ e endpoint interno.
- `src/test`: unidade, segurança e integração com Kafka/PostgreSQL/Redis/Registry reais.
- `ops`: alertas e runbooks pertencentes ao SBUS.
- `deploy`: gates da imagem e do Compose.
- `docs`: arquitetura, contratos, configuração, segurança, operação e ADRs locais.
- `.github`: pipeline extraível; não publica nem faz deploy.

## Fontes de verdade

1. código, configuração, migrations e testes desta raiz;
2. GAVs publicados por `payment-contracts`;
3. Dockerfile, Compose e scripts executáveis locais;
4. ADRs aceitos e documentação local;
5. decisões cross-boundary em `../.specs/STATE.md` enquanto o workspace existir.

## Invariantes

- Estado e outbox mudam na mesma transação; publish ocorre fora dela.
- Retry é persistido antes do offset, respeita `not-before` e não dorme na partição.
- DLQ permanece recuperável e alertável até confirmação do broker.
- Primeiro terminal vence; redelivery não muda resultado terminal.
- Retenções cobrem replay, deduplicação e consulta; migrations Flyway são append-only.
- Kafka, PostgreSQL, Redis e Registry usam budgets finitos e participam de readiness.
- O Compose cria somente o SBUS e usa a rede externa do sandbox.
- A imagem usa UID/GID 10001, filesystem read-only e capabilities removidas.

## Limites de ownership e ações proibidas

- Não copie schemas nem sources de contratos; consuma GAV versionado.
- Não crie Kafka, PostgreSQL, Redis, Registry ou observabilidade neste Compose.
- Não edite migrations aplicadas, enfraqueça testes ou confirme offset antes do estado recuperável.
- Não exponha métricas/detalhes de health ou o endpoint interno anonimamente.
- Não registre segredos, tokens, payload sensível ou conteúdo de `.env`.
- Não faça push, deploy ou publicação externa sem autorização específica.

## Gates

```bash
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
./gradlew build --no-daemon
scripts/verify-docs.sh
deploy/verify.sh --structural
git diff --check
```

`deploy/verify.sh` sem `--structural` executa o smoke da imagem e exige Docker e sandbox saudável. Ausência dessas dependências é `NOT_RUN`, nunca aprovação.
