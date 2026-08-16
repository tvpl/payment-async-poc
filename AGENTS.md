# Guia do workspace para agentes

Este arquivo governa apenas mudanças cross-boundary. Detalhes de produto pertencem ao `AGENTS.md` local e à documentação da fronteira proprietária. Não copie regras locais para esta raiz.

## Estado e objetivo

O workspace é composto por sete raízes autossuficientes, cada uma com build, testes, documentação, CI e instruções de IA locais.

| Fronteira | Escopo |
| --- | --- |
| `payment-contracts` | contratos e compatibilidade de eventos |
| `payment-api` | API e coordenação de resposta |
| `payment-sbus` | processamento durável e integração com o Core |
| `payment-core-mock` | simulador `NON_PRODUCTION` |
| `feature-control` | biblioteca de controle de features e exemplos locais |
| `async-redis-service` | exemplo async-to-sync baseado em Redis |
| `sandbox` | infraestrutura local compartilhada e observabilidade |

`feature-demo` e `pilot-app` são exemplos internos de `feature-control`, não fronteiras próprias. Os três permanecem `NON_PRODUCTION` junto com `payment-core-mock`.

## Fontes de verdade

Use esta ordem quando houver divergência:

1. código e testes da fronteira proprietária;
2. build, configuração executável, migrations e schemas da fronteira;
3. `AGENTS.md` local e ADRs locais;
4. contratos publicados e fixtures de consumo;
5. decisões transversais em [.specs/STATE.md](.specs/STATE.md);
6. especificação, desenho e tarefas aprovadas em [.specs/features/repository-segregation-production-hardening](.specs/features/repository-segregation-production-hardening/spec.md);
7. documentação de workspace em [docs](docs/workspace-overview.md).

Não altere código para coincidir com texto desatualizado. Confirme a intenção na fonte executável e corrija a documentação na tarefa de seu owner.

## Invariantes cross-boundary

- Cada raiz standalone compila e testa sem ler build, wrapper ou fontes de outra raiz.
- Dependências entre fronteiras usam GAV versionado. `project(':...')` não atravessa fronteiras.
- Composite build é opcional e explícito. Gates de release desabilitam substitution e consomem o artefato publicado.
- Somente `sandbox` cria infraestrutura local compartilhada. Composes de aplicação conectam à rede externa do sandbox.
- Schemas Avro, tópicos, headers, endpoints e tabelas Postgres não quebram entre versões sem uma decisão registrada. Migrations Flyway são append-only.
- O gate de equivalência registra perda, alteração, adição e duplicação estrutural. Não atualize o baseline histórico para esconder divergência real.
- Nenhum segredo real, token ou conteúdo de `.env` entra em source, docs, logs, testes ou build context.
- Um claim de produção exige gate e relatório datado. Exemplos e mocks usam `NON_PRODUCTION` de forma explícita.
- Push, deploy, publicação externa, criação de repositório remoto e mudança em produção exigem autorização própria.

## Como executar uma mudança

1. Identifique o owner no mapa acima.
2. Leia o `AGENTS.md` local da fronteira de destino, seu build, configuração, testes e docs.
3. Leia a tarefa e os requisitos correspondentes na [spec aprovada](.specs/features/repository-segregation-production-hardening/spec.md), quando aplicável.
4. Declare arquivos, pressupostos e gate antes de editar.
5. Faça uma mudança por tarefa. Preserve alterações locais alheias e não use `git stash`.
6. Derive testes dos critérios de aceitação. Não apague, ignore ou enfraqueça testes.
7. Execute primeiro o gate local e depois o gate cross-boundary proporcional ao risco.
8. Atualize documentação e ADR do owner quando mudar contrato, operação, configuração ou garantia.

## Gates

Cada raiz roda seu próprio wrapper Gradle; a raiz do workspace não é fonte de versões nem build agregador.

```bash
cd <fronteira> && ./gradlew build --no-daemon
cd <fronteira> && ./gradlew test -PwithIT --no-daemon   # quando a fronteira tiver testes *IT
```

Testes `*IT` exigem Docker ou a dependência externa documentada localmente. Ausência de Docker deve aparecer como `NOT_RUN`, nunca como aprovação.

Gates cross-boundary, a partir da raiz do workspace:

```bash
scripts/verify-workspace.sh
python3 scripts/equivalence/equivalence.py verify --root . --manifest scripts/equivalence/baseline-manifest.json
python3 scripts/docs/validate_docs.py
python3 scripts/workspace/check_root_governance.py
git diff --check
```

Use `python3 scripts/equivalence/equivalence.py generate` para revisar uma mudança estrutural intencional antes de atualizar o baseline no mesmo commit que a prova.

## Ownership documental

- A raiz contém somente mapa, decisões transversais, orquestração e gates conjuntos.
- Arquitetura, contratos, configuração, segurança, operação, observabilidade, testes e performance pertencem ao owner local.
- ADRs locais registram decisões da fronteira. Decisões que afetam todo o workspace também entram em [.specs/STATE.md](.specs/STATE.md).
- Um documento só pode ser removido depois de ter destino explícito e de passar validação de links e claims.

## Checklist de encerramento

- O diff está limitado ao owner e à tarefa declarados.
- O gate local passou e a contagem de testes não diminuiu.
- Contratos, migrations, configuração e docs continuam coerentes.
- O gate de equivalência explica toda divergência intencional.
- `git diff --check` passou.
- Não houve efeito remoto ou destrutivo fora da autorização.
