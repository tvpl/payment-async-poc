# Guia local para agentes

Estas instruções governam somente `payment-contracts`. Regras cross-boundary permanecem no `AGENTS.md` do workspace.

## Responsabilidade

- `schemas/`: fonte de verdade Avro, manifest, política e histórico imutável publicado.
- `contract-model/`: envelope, modelos, constantes e código Avro gerado no build.
- `contract-avro-apicurio/`: mapper e adapter limitado de Registry.
- `consumer-fixture/`: prova de consumo dos GAVs publicados, sem substitution.
- `docs/`: arquitetura, contrato, operação e decisões desta fronteira.
- `scripts/`: gates locais de publicação, consumo e documentação.

## Fontes de verdade

1. schemas atuais, manifest e histórico;
2. código e testes dos dois módulos;
3. build e POMs gerados;
4. ADRs aceitos;
5. documentação local.

Não altere código para reproduzir texto legado. Corrija a fonte obsoleta depois de confirmar o comportamento executável.

## Invariantes

- Os `.avsc` diretamente em `schemas/` são a única fonte editável. Nunca edite fontes Avro geradas em `build/`.
- Preserve packages, event types, tópicos, headers, envelope e representação Avro existente.
- Mudança compatível passa `FULL_TRANSITIVE` contra todas as versões em `schemas/history/`.
- Mudança incompatível exige novo major, artifact id, tópico, coexistência e ADR. Não substitua o contrato anterior.
- Produção usa schema registrado previamente e `payments.avro.auto-register=false`.
- Os dois módulos publicados não recebem controller, rate limiter, persistência, regra de aplicação ou dependência por source path externa.
- O pool de codecs mantém capacidade e timeout finitos. Não reintroduza `ThreadLocal` por virtual thread.
- Consumidores reais usam os GAVs publicados. Composite é opt-in local e nunca integra gate de release.

## Ações proibidas

- Editar ou apagar histórico de schema publicado.
- Renomear package, tipo, tópico ou header sem versionamento coexistente.
- Adicionar Dockerfile, Compose ou infraestrutura a esta biblioteca.
- Ler `settings.gradle`, build, wrapper ou fontes de outra fronteira.
- Habilitar auto-registration no profile produtivo.
- Publicar externamente, criar repositório remoto ou fazer push sem autorização própria.

## Fluxo de alteração

1. Atualize o schema atual e o manifest juntos.
2. Acrescente a versão publicada ao histórico; nunca reescreva diretório histórico.
3. Atualize mapper, exemplos e testes de todos os campos afetados.
4. Execute compatibilidade antes de publicar localmente.
5. Compile o consumer fixture com substitution desabilitada.
6. Atualize contrato, operação e ADR quando houver trade-off novo.

## Gates

```bash
./gradlew test -PwithIT --no-daemon
./gradlew checkSchemaCompatibility --no-daemon
./gradlew build publishAllToLocalBuildRepository verifyLocalPublication --no-daemon
scripts/verify-consumer-fixture.sh
scripts/verify-docs.sh
```

Integração com Registry externo sem ambiente disponível é `NOT_RUN`, nunca `PASS`. Sempre execute também `git diff --check` no workspace antes do commit.
