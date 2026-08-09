# Guia do sandbox para agentes

Este arquivo governa somente `sandbox/`. Código, schema e dashboard de produto pertencem à fronteira da aplicação ou de contratos.

## Escopo

- `compose.yml`: Kafka, Redis, PostgreSQL, Registry, rede e volumes mínimos.
- `compose.profiles.yml`: observabilidade comum e ferramentas locais opcionais.
- `config/`: tópicos locais e lifecycle executável.
- `observability/`: configuração de infraestrutura e manifest de assets externos.
- `smoke/`: inicialização, readiness, profiles, portas e lifecycle.
- `scripts/`: operação explícita que não pertence a smoke/build.
- `docs/`: fonte operacional desta fronteira.

## Fontes de verdade

1. Compose renderizado e scripts executáveis.
2. `config/lifecycle.json` e `.env.example`.
3. Smoke e testes locais.
4. ADRs e documentação local.
5. Decisões cross-boundary em `../.specs/STATE.md` enquanto este workspace existir.

## Invariantes

- Não adicionar build, fonte, migration, schema ou mock de aplicação.
- Somente o sandbox cria a rede nomeada e infraestrutura compartilhada local.
- Composes de aplicação conectam à rede como `external: true` e não duplicam infraestrutura.
- O minimal não carrega secrets, ferramentas ou observabilidade opcionais.
- Toda imagem usa tag e digest. Atualização de digest exige pull, smoke e revisão do lifecycle.
- Assets de produto não são copiados. O manifest contém apenas referências versionadas dos owners.
- Smoke e build nunca executam `scripts/reset-data.sh`.
- Não alterar ou remover volumes sem autorização explícita e confirmação destrutiva.

## Gates

```bash
cd sandbox
make config
make verify-structural
make verify-runtime
git diff --check
```

Docker indisponível ou profile não iniciado é `NOT_RUN`/falha, nunca PASS presumido.

## Ações proibidas

- Executar reset, prune, `down -v` ou remover volume como parte de validação.
- Inserir segredo real em `.env.example`, docs, logs ou commits.
- Copiar dashboards/alerts de aplicação para esta fronteira.
- Fazer push, deploy ou alterar infraestrutura remota sem autorização própria.
