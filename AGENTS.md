# Guia do workspace para agentes

Este arquivo governa apenas mudanças cross-boundary e a migração do workspace. Detalhes de produto pertencem ao `AGENTS.md` e à documentação da fronteira proprietária. Não copie regras locais para esta raiz.

## Estado e objetivo

O workspace está migrando de um build Gradle compartilhado para sete raízes extraíveis. Durante a transição, localização atual e owner de destino coexistem no mapa abaixo. A localização atual não redefine a fronteira arquitetural.

| Owner de destino | Localização transitória | Escopo |
| --- | --- | --- |
| `payment-contracts` | `common` | contratos e compatibilidade de eventos |
| `payment-api` | `api-service` | API e coordenação de resposta |
| `payment-sbus` | `sbus-service` | processamento durável e integração com o Core |
| `payment-core-mock` | `core-mock` | simulador `NON_PRODUCTION` |
| `feature-control` | `feature-control`, `feature-demo`, `pilot-app` | biblioteca e exemplos locais |
| `async-redis-service` | `async-redis-service` | exemplo Redis independente |
| `sandbox` | `docker-compose.yml`, `observability`, `deploy` | infraestrutura local compartilhada |

`feature-demo` e `pilot-app` são exemplos internos de `feature-control`, não novas fronteiras. Os três permanecem `NON_PRODUCTION` junto com `payment-core-mock`.

## Fontes de verdade

Use esta ordem quando houver divergência:

1. código e testes da fronteira proprietária;
2. build, configuração executável, migrations e schemas da fronteira;
3. `AGENTS.md` e ADRs locais, quando já existirem;
4. contratos publicados e fixtures de consumo;
5. decisões transversais em [.specs/STATE.md](.specs/STATE.md);
6. especificação, desenho e tarefas aprovadas em [.specs/features/repository-segregation-production-hardening](.specs/features/repository-segregation-production-hardening/spec.md);
7. documentação central legada em [docs](docs/README.md).

Não altere código para coincidir com texto legado. Confirme a intenção na fonte executável e corrija a fonte obsoleta na tarefa de seu owner.

## Invariantes cross-boundary

- Cada raiz standalone compila e testa sem ler build, wrapper ou fontes de outra raiz.
- Dependências entre fronteiras usam GAV versionado. `project(':...')` não atravessa fronteiras.
- Composite build é opcional e explícito. Gates de release desabilitam substitution e consomem o artefato publicado.
- Somente `sandbox` cria infraestrutura local compartilhada. Composes de aplicação conectam à rede externa do sandbox.
- Schemas Avro, tópicos, headers, endpoints e tabelas não quebram durante a migração. Migrations Flyway são append-only.
- O gate de equivalência registra perda, alteração, adição e duplicação. Não atualize o baseline histórico para esconder divergência.
- Nenhum segredo real, token ou conteúdo de `.env` entra em source, docs, logs, testes ou build context.
- Um claim de produção exige gate e relatório datado. Exemplos e mocks usam `NON_PRODUCTION` de forma explícita.
- Push, deploy, publicação externa, criação de repositório remoto e mudança em produção exigem autorização própria.

## Como executar uma mudança

1. Identifique o owner no mapa.
2. Leia o `AGENTS.md` local se a raiz de destino já existir. Durante a transição, leia o build, configuração, testes e docs da localização transitória.
3. Leia a tarefa e os requisitos correspondentes na [spec aprovada](.specs/features/repository-segregation-production-hardening/spec.md).
4. Declare arquivos, pressupostos e gate antes de editar.
5. Faça uma mudança por tarefa. Preserve alterações locais alheias e não use `git stash`.
6. Derive testes dos critérios de aceitação. Não apague, ignore ou enfraqueça testes.
7. Execute primeiro o gate local e depois o gate cross-boundary proporcional ao risco.
8. Atualize documentação e ADR do owner quando mudar contrato, operação, configuração ou garantia.

## Gates transitórios

Até a extração das raízes, use:

```bash
./gradlew :<modulo-atual>:test --no-daemon
./gradlew test --no-daemon
./gradlew build --no-daemon
docker compose config -q
python3 scripts/workspace/check_root_governance.py
git diff --check
```

Testes `*IT` entram com `-PwithIT` e exigem Docker ou a dependência externa documentada. Ausência de Docker deve aparecer como `NOT_RUN`, nunca como aprovação.

Use `python3 scripts/equivalence/equivalence.py verify --root . --manifest scripts/equivalence/baseline-manifest.json` para listar divergências contra o baseline histórico. Durante os moves, uma divergência intencional continua explícita até seu destino e sua equivalência serem reconciliados; não altere o baseline para forçar verde.

Quando uma raiz standalone existir, seu wrapper e seus comandos locais substituem o recorte `:<modulo-atual>`. A raiz do workspace não será a fonte de versões nem um build agregador permanente.

## Ownership documental

- A raiz contém somente mapa, decisões transversais, orquestração e gates conjuntos.
- Arquitetura, contratos, configuração, segurança, operação, observabilidade, testes e performance pertencem ao owner local.
- ADRs locais registram decisões da fronteira. Decisões que afetam todo o workspace também entram em [.specs/STATE.md](.specs/STATE.md).
- Um documento legado só pode ser removido depois de ter destino explícito e de passar validação de links e claims.

## Checklist de encerramento

- O diff está limitado ao owner e à tarefa declarados.
- O gate local passou e a contagem de testes não diminuiu.
- Contratos, migrations, configuração e docs continuam coerentes.
- O gate de equivalência explica toda divergência intencional.
- `git diff --check` passou.
- Não houve efeito remoto ou destrutivo fora da autorização.
