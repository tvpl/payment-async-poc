# Guia local para agentes

Estas instruções governam somente `payment-core-mock`. Regras cross-boundary permanecem no `AGENTS.md` do workspace.

## Classificação e responsabilidade

Esta fronteira é `NON_PRODUCTION`: um simulador determinístico da integração de pagamento para desenvolvimento e testes. Ela não é promovível por profile e não representa uma dependência externa certificada.

- `src/main`: consumo Kafka, decisão determinística e resposta simulada.
- `src/test`: unidade, contratos e integração com Kafka/Apicurio reais em contêineres.
- `deploy`: gates estruturais e smoke da imagem.
- `docs`: arquitetura, contratos, operação, limitações e decisões locais.
- `.github/workflows`: gates preparados para a raiz extraída; não publica nem faz deploy.

## Fontes de verdade

1. código, configuração e testes desta raiz;
2. GAVs publicados por `payment-contracts`;
3. Dockerfile, Compose renderizado e scripts executáveis;
4. ADRs aceitos e documentação local;
5. decisões cross-boundary em `../.specs/STATE.md` enquanto este workspace existir.

## Invariantes

- `NON_PRODUCTION` permanece visível no startup, imagem, README e CI.
- A mesma combinação de `CORE_SEED` e `requestId` produz outcome, latência e autorização equivalentes.
- Percentuais ficam entre 0 e 100, a soma não excede 100 e os limites de latência são não negativos e ordenados.
- Tópicos, Avro, headers, correlação e causação vêm dos GAVs versionados; não copie schemas ou fontes de contratos.
- Falha de decode, Registry ou simulação não avança silenciosamente o offset da partição.
- O Compose possui somente esta aplicação e conecta à rede externa criada pelo sandbox.
- A imagem executa como UID/GID 10001, filesystem read-only e sem capabilities Linux.

## Ações proibidas

- Declarar SLA, capacidade, semântica financeira ou prontidão de uma integração externa com base neste simulador.
- Usar resposta aleatória, relógio local ou estado mutável para decidir outcomes.
- Adicionar Kafka, Registry, banco, Redis ou observabilidade compartilhada ao Compose local.
- Referenciar build/source de outra fronteira com `project(...)` ou copiar seus schemas.
- Confirmar, ignorar ou pular registro poison para tornar um teste verde.
- Fazer push, deploy, publicar artefato ou alterar ambiente remoto sem autorização própria.

## Fluxo de alteração

1. Derive testes da mudança de comportamento ou contrato.
2. Preserve determinismo e valide configurações no startup.
3. Execute testes unitários antes dos ITs Kafka/Apicurio.
4. Atualize documentação e ADR se mudar contrato, operação ou limitação.
5. Valide imagem e Compose sem remover volumes ou infraestrutura do sandbox.

## Gates

```bash
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
./gradlew build --no-daemon
scripts/verify-docs.sh
deploy/verify.sh
git diff --check
```

Docker ou dependência externa indisponível é `NOT_RUN`, nunca aprovação presumida. `deploy/verify.sh` remove somente seu projeto Compose efêmero e nunca usa `down -v`, prune ou remoção de imagens.
