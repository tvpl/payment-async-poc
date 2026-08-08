# Orientação do Codebase para IA

## Problem Statement

O repositório tem documentação humana extensa, mas não oferece um ponto de entrada rastreado e conciso para agentes de IA. A visão arquitetural principal também descreve apenas o fluxo de pagamentos e não posiciona todos os oito módulos Gradle atuais.

## Goals

- [ ] Dar a agentes de IA um mapa verificável do codebase, das fontes de verdade e dos limites arquiteturais.
- [ ] Alinhar a documentação de arquitetura e os índices com todos os módulos atuais.
- [ ] Documentar comandos de validação proporcionais ao tipo de mudança.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Alterar código de produção, contratos ou infraestrutura | O pedido é de análise e configuração documental. |
| Rastrear skills locais em `.agents/`, `.claude/` ou `.cursor/` | Esses diretórios já existem como configuração local não rastreada e não são fonte arquitetural. |
| Redesenhar a arquitetura | A documentação deve refletir o sistema implementado. |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Idioma dos artefatos | pt-BR, preservando nomes técnicos do código | É o idioma adotado por toda a documentação atual. | y |
| Ponto de entrada para agentes | `AGENTS.md` na raiz | Mantém uma fonte canônica, rastreada e independente de editor. | y |
| Profundidade | Mapa curto com links para documentos especializados | Evita duplicação e divergência da documentação existente. | y |
| Precedência em divergências | Código, configuração executável e migrations prevalecem | São as fontes que determinam o comportamento construído. | y |
| Demais dimensões implícitas | N/A para este escopo documental | Não há mudança de estado, API, autenticação, persistência ou concorrência em runtime. | y |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Contexto confiável para agentes ⭐ MVP

**User Story**: Como agente de engenharia, quero localizar rapidamente arquitetura, contratos, convenções e comandos para alterar o projeto sem violar suas garantias.

**Why P1**: Sem contexto canônico, cada agente precisa reconstruir o sistema e pode confiar em documentação parcial.

**Acceptance Criteria**:

1. WHEN um agente abrir o repositório THEN o codebase SHALL fornecer na raiz um `AGENTS.md` com visão arquitetural, fontes de verdade, limites de mudança e comandos de validação.
2. The `AGENTS.md` SHALL mapear nominalmente os oito módulos declarados em `settings.gradle` para suas responsabilidades implementadas.
3. WHEN uma mudança tocar eventos, persistência, processamento assíncrono ou feature flags THEN o `AGENTS.md` SHALL indicar os invariantes e as fontes de verdade que precisam ser preservados.
4. IF um comando depender de Docker ou serviços externos THEN a orientação SHALL declarar essa dependência e oferecer o gate local sem Docker quando existente.

**Independent Test**: Comparar o guia com `settings.gradle`, Gradle, CI, Compose, migrations e os principais entrypoints Java.

### P1: Arquitetura e navegação atualizadas

**User Story**: Como pessoa ou agente que entra no projeto, quero que os índices e a visão arquitetural representem o codebase atual.

**Why P1**: O documento de arquitetura atual cobre o núcleo de pagamentos, mas omite os módulos de feature control, demonstração, Redis assíncrono e adoção.

**Acceptance Criteria**:

1. WHEN alguém consultar `docs/02-arquitetura.md` THEN a documentação SHALL distinguir o fluxo principal de pagamentos, a capacidade transversal de feature control e o exemplo alternativo async-to-sync via Redis.
2. The documentação SHALL apontar para o guia de IA a partir dos índices da raiz e de `docs/README.md`.
3. IF um link Markdown relativo for adicionado ou alterado THEN o repositório SHALL resolver o destino para um arquivo existente.

**Independent Test**: Executar um verificador de links locais e confrontar a arquitetura com a estrutura de módulos.

## Edge Cases

- IF a documentação e o código divergirem THEN a orientação SHALL mandar verificar código, configuração executável e migrations antes de editar.
- IF os testes de integração forem executados sem Docker ou Redis externo THEN a orientação SHALL separar o gate unitário do gate de integração.
- IF uma mudança afetar apenas um módulo THEN a orientação SHALL recomendar o menor gate Gradle desse módulo antes do gate completo.

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| AIDOC-01 | P1: Contexto confiável para agentes | Execute | Implementing |
| AIDOC-02 | P1: Contexto confiável para agentes | Execute | Implementing |
| AIDOC-03 | P1: Contexto confiável para agentes | Execute | Implementing |
| AIDOC-04 | P1: Contexto confiável para agentes | Execute | Implementing |
| AIDOC-05 | P1: Arquitetura e navegação atualizadas | Execute | Pending |
| AIDOC-06 | P1: Arquitetura e navegação atualizadas | Execute | Pending |
| AIDOC-07 | P1: Arquitetura e navegação atualizadas | Execute | Pending |

**Coverage:** 7 total, 7 mapped to execution steps, 0 unmapped.

## Success Criteria

- [ ] Um agente consegue identificar os oito módulos e escolher os documentos e gates corretos sem varrer todo o repositório.
- [ ] A documentação não contradiz `settings.gradle`, `build.gradle`, CI, Compose ou os entrypoints inspecionados.
- [ ] Todos os links locais alterados resolvem para arquivos existentes.
