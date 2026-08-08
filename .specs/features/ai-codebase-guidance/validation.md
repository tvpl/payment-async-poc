# Validação: ai-codebase-guidance — PASS

**Data**: 2026-08-08
**Spec**: `.specs/features/ai-codebase-guidance/spec.md`
**Diff**: `dea89b9..HEAD` (`4603434`, `1908abf`, `e3cc836`)
**Verificador**: subagente independente, diferente do autor

---

## Escopo e conclusão

**PASS.** Os sete critérios de aceitação têm evidência executável sobre os documentos alterados. O gate Gradle, o validador estrutural da spec, o verificador de links locais e o sensor documental passaram.

O intervalo contém os quatro documentos de implementação declarados (`AGENTS.md`, `README.md`, `docs/README.md`, `docs/02-arquitetura.md`) e `.specs/features/ai-codebase-guidance/spec.md`, que é artefato de planejamento. Não há mudança em produção, testes, contratos ou infraestrutura.

## Conclusão das tarefas

| Item | Status | Evidência |
| --- | --- | --- |
| Implementação documental | ✅ Concluída | Os quatro arquivos declarados estão no diff. |
| Plano de tarefas | N/A | Não existe `tasks.md`; não há estado de tarefa para conferir. |

## Critérios de aceitação ancorados na spec

| ID | Critério e resultado definido pela spec | Evidência `arquivo:linha` e expressão de verificação | Resultado |
| --- | --- | --- | --- |
| AIDOC-01 | Na raiz, `AGENTS.md` fornece visão, fontes, limites/invariantes e gates. | `AGENTS.md:3`, `AGENTS.md:15`, `AGENTS.md:65`, `AGENTS.md:111`; `for required_heading in ...; do rg -Fqx "$required_heading" AGENTS.md; done` | ✅ PASS |
| AIDOC-02 | Os oito módulos de `settings.gradle` são mapeados nominalmente às responsabilidades. | `settings.gradle:15`, `settings.gradle:22`; `AGENTS.md:33`, `AGENTS.md:40`; `for module_name in $(rg -o "include '[^']+'" settings.gradle ...); do rg -Fq "| \`$module_name\`" AGENTS.md; done` | ✅ PASS |
| AIDOC-03 | Eventos, persistência, assíncrono e flags têm invariantes e fontes de verdade a preservar. | `AGENTS.md:17`, `AGENTS.md:67`, `AGENTS.md:76`, `AGENTS.md:85`, `AGENTS.md:94`; `rg -Fq '### Eventos e contratos' ... '### Feature flags e segurança' AGENTS.md` | ✅ PASS |
| AIDOC-04 | Dependência de Docker/serviços externos é declarada e há gate local sem Docker. | `AGENTS.md:113`, `AGENTS.md:117`, `AGENTS.md:135`, `AGENTS.md:137`; `rg -Fq './gradlew :<modulo>:test' AGENTS.md && rg -Fq 'Testcontainers e precisa de Docker' AGENTS.md` | ✅ PASS |
| AIDOC-05 | A arquitetura separa pagamentos Kafka, feature control transversal e alternativa Redis. | `docs/02-arquitetura.md:22`, `docs/02-arquitetura.md:24`, `docs/02-arquitetura.md:48`, `docs/02-arquitetura.md:62`; `rg -Fq 'O repositório reúne três capacidades.' docs/02-arquitetura.md && rg -Fq 'independente, sem Kafka ou Postgres' docs/02-arquitetura.md` | ✅ PASS |
| AIDOC-06 | Índices da raiz e de `docs/` apontam ao guia de IA. | `README.md:22`; `docs/README.md:11`, `docs/README.md:18`, `docs/README.md:27`; `rg -Fq '](AGENTS.md)' README.md && rg -Fq '](../AGENTS.md)' docs/README.md` | ✅ PASS |
| AIDOC-07 | Cada link Markdown local alterado resolve para arquivo existente. | `README.md:19`, `README.md:22`; `docs/README.md:8`, `docs/README.md:11`; `docs/02-arquitetura.md:18`; loop sobre `rg --with-filename --no-line-number -o '\\]\\([^)]+' AGENTS.md README.md docs/README.md docs/02-arquitetura.md` com `test -e "$(dirname "$link_file")/$link_target"` | ✅ PASS |

**Resultado ancorado na spec**: 7/7 critérios correspondem ao resultado definido, sem lacuna de precisão. Como esta feature é documental, as asserções são checks estruturais dos artefatos, não testes Java novos.

## Casos de borda

- [x] Divergência documentação/código: `AGENTS.md:17` a `AGENTS.md:25` determina precedência para código, configuração executável e migrations.
- [x] Integração sem Docker/Redis externo: `AGENTS.md:113` a `AGENTS.md:149` separa unitários, integração com Docker e `REDIS_TEST_URI`.
- [x] Mudança de um módulo: `AGENTS.md:115` a `AGENTS.md:123` prescreve `:<modulo>:test` antes do gate completo.

## Gates e checks

| Comando | Resultado |
| --- | --- |
| `./gradlew build --no-daemon` | ✅ `BUILD SUCCESSFUL`; 79 tarefas, 0 falhas. Testes ficaram `UP-TO-DATE`; o diff não altera arquivos `*Test*` ou `*IT*`, portanto não houve redução de testes atribuível à feature. |
| `python3 .agents/skills/tlc-spec-driven/scripts/validate_spec.py ai-codebase-guidance --root .` | ✅ 0 erros, 0 avisos. |
| Check documental dos ACs com `rg` e módulos extraídos de `settings.gradle` | ✅ PASS. |
| Check de links locais Markdown nos quatro documentos | ✅ PASS. |
| `git diff --check dea89b9..HEAD` | ✅ Sem whitespace inválido. |

## Sensor de discriminação

**Profundidade**: leve, proporcional a documentação. As mutações foram feitas somente em cópias temporárias sob `/private/tmp`; nenhum build Gradle foi repetido, pois cada check estrutural é a asserção diretamente ancorada ao AC.

| Mutação | Arquivo e linha original | Check que falhou | Resultado |
| --- | --- | --- | --- |
| Remover a linha do módulo `pilot-app`. | `AGENTS.md:40` | Comparação da tabela contra os `include` de `settings.gradle`; falhou com `missing pilot-app`. | ✅ Morta |
| Trocar o destino `AGENTS.md` por `AGENT.md`. | `README.md:22` | Verificador de links locais; falhou com `README.md -> AGENT.md`. A cópia não mutada passou antes da injeção. | ✅ Morta |
| Trocar a separação Redis por integração com Kafka/Postgres. | `docs/02-arquitetura.md:24` | `rg -Fq 'independente, sem Kafka ou Postgres' docs/02-arquitetura.md`; falhou pela ausência da afirmação exigida. | ✅ Morta |

**Resultado**: 3/3 mutações mortas, 0 sobreviventes. Após remover os diretórios temporários, `git status --porcelain=v1` da árvore real permaneceu exatamente no baseline: `?? .agents/`, `?? .claude/`, `?? .cursor/`.

## Qualidade

| Princípio | Status |
| --- | --- |
| Mudança mínima e sem escopo além da feature | ✅ |
| Alterações cirúrgicas e padrão documental existente | ✅ |
| Sem abstração ou flexibilidade especulativa | ✅ |
| Testes/checks mapeiam 1:1 aos critérios documentais | ✅ |
| Guidelines seguidas | ✅ `AGENTS.md` e `.agents/skills/tlc-spec-driven/references/coding-principles.md` |

Não há UAT: a entrega é documentação de engenharia e os resultados observáveis são os checks estruturais. Nenhuma lesson foi registrada, pois o resultado é PASS limpo, sem mutant sobrevivente, lacuna de precisão, AC descoberto ou desvio de spec.

## Sumário

**Overall**: ✅ Ready

**Spec-anchored check**: 7/7 ACs com resultado correspondente.
**Sensor**: 3/3 mutações mortas.
**Gate**: Gradle build passou, 0 falhas.
