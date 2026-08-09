# Segregação de Repositórios e Hardening de Produção — Tasks

## Execution Protocol (MANDATORY -- do not skip)

Implemente estas tarefas com a skill `tlc-spec-driven`: ative-a pelo nome e siga integralmente seu fluxo Execute e Critical Rules. Não procure a skill por um caminho fixo durante a execução.

Se a skill não puder ser ativada, interrompa e informe o usuário. Cada tarefa termina com gate verde, atualização deste arquivo e da rastreabilidade, e um Conventional Commit atômico. Push, deploy, publicação externa, exclusão destrutiva e criação de repositórios remotos continuam fora da autorização.

**Design:** `.specs/features/repository-segregation-production-hardening/design.md`  
**Status:** In Progress  
**Baseline:** 2026-08-08; `./gradlew test --no-daemon` e `docker compose config -q` passaram.

---

## Test Coverage Matrix

> Gerada a partir de `AGENTS.md`, `docs/13-testes.md`, `build.gradle`, `.github/workflows/ci.yml` e amostra de dez testes JUnit/Micronaut/Testcontainers. A cobertura existente define estilo e baseline, não o teto; os acceptance criteria e edge cases da spec definem a profundidade.

| Code Layer | Required Test Type | Coverage Expectation | Location Pattern | Run Command |
| --- | --- | --- | --- | --- |
| Domain/service/state machine | unit | Todos os branches; mapeamento 1:1 aos ACs; todo edge case relevante | `<root>/src/test/java/**/*UnitTest.java` | `<root>/gradlew test --no-daemon` |
| Controller/security/profile | integration | Todas as rotas: happy, authn/authz, config inválida, edge e falha | `<root>/src/test/java/**/*IT.java` | `<root>/gradlew test -PwithIT --no-daemon` |
| Kafka/Avro/Registry contract | contract + integration | Round-trip, headers, compatibilidade, duplicata, poison e indisponibilidade | `payment-contracts/**/src/test` e apps `**/*IT.java` | wrapper da fronteira com `test -PwithIT` |
| PostgreSQL/outbox/retry | integration | Queries/índices/transações, crash windows, lease, retry due e DLQ | `payment-sbus/src/test/java/**/*IT.java` | `payment-sbus/gradlew test -PwithIT --no-daemon` |
| Redis coordination/Streams | integration | Lua atômico, TTL, pool exhaustion, PEL, reclaim, restart e duas instâncias | apps `src/test/java/**/*IT.java` | wrapper da fronteira com `test -PwithIT` |
| Feature resolution | unit + integration | Tipos/limites, bucketing, stale/fallback, CAS, pubsub, auditoria e cardinalidade | `feature-control/**/src/test` | `feature-control/gradlew test -PwithIT --no-daemon` |
| Build/publication/container/config | structural | Build isolado, POM/sources/Javadoc, artifact-only consumer, profile guard, image e Compose | build/CI/smoke da fronteira | build da fronteira e verificação estrutural aplicável |
| Documentation/ADR/AGENTS | structural | Links, comandos, portas, variáveis, claims, ownership e índice ADR válidos | `<root>/docs`, `README.md`, `AGENTS.md` | `scripts/verify-docs.sh` |
| Cross-boundary workflow | e2e + performance | Duas APIs/SBUS, falhas, zero perda aceita, steady/spike/soak/slowdown/recovery | `scripts/e2e`, `load`, relatórios | `scripts/verify-workspace.sh` e gate de carga |

Baseline mínimo que nunca pode diminuir silenciosamente: contracts/common 5 métodos, API 9, SBUS 7, Core mock 0, feature-control + exemplos 31 e async Redis 6. Cada tarefa de código acrescenta os testes explicitamente pedidos sem apagar ou enfraquecer os anteriores.

## Gate Check Commands

> Os comandos standalone tornam-se disponíveis quando a respectiva raiz for criada. Antes disso, a tarefa usa o gate equivalente do build raiz registrado em seu `Done when`.

| Gate Level | When to Use | Command |
| --- | --- | --- |
| Quick | Lógica/unit tests da fronteira | `./gradlew test --no-daemon` executado na raiz da fronteira |
| Full | Integração, segurança, Kafka, DB ou Redis | `./gradlew test -PwithIT --no-daemon` executado na raiz da fronteira |
| Build | Fim de fase e packaging | `./gradlew build --no-daemon` + publicação/Compose/docs aplicáveis |
| Sandbox | Infraestrutura | `docker compose --profile observability --profile tools config -q` + `sandbox/smoke/verify.sh` |
| Workspace | Cross-boundary/release | `scripts/verify-workspace.sh` |
| Hygiene | Toda tarefa | `git diff --check` e contagem de testes sem redução |

---

## Execution Plan

Fases e tarefas são estritamente sequenciais. A execução de uma fase só começa após o gate da anterior.

```text
Phase 1: T1 -> T2 -> T3 -> T4 -> T5 -> T6
Phase 2: T7 -> T8 -> T9 -> T10 -> T11 -> T12
Phase 3: T13 -> T14 -> T15 -> T16 -> T17 -> T18
Phase 4: T19 -> T20 -> T21 -> T22 -> T23
Phase 5: T24 -> T25 -> T26 -> T27 -> T28 -> T29 -> T30
Phase 6: T31 -> T32 -> T33 -> T34 -> T35 -> T36 -> T37
Phase 7: T38 -> T39 -> T40 -> T41 -> T42 -> T43 -> T44 -> T45
Phase 8: T46 -> T47 -> T48 -> T49 -> T50 -> T51 -> T52 -> T53
Phase 9: T54 -> T55 -> T56 -> T57 -> T58 -> T59 -> T60
```

---

## Task Breakdown

### Phase 1 — Controles de migração e governança

#### T1: Criar manifest e gate de equivalência do baseline

**Status:** Complete

**What:** Registrar inventário, checksums e contagens de fontes, testes, migrations, schemas, tópicos, dashboards, scripts e documentos válidos; criar verificador determinístico.  
**Where:** `scripts/equivalence`  
**Depends on:** None  
**Reuses:** `settings.gradle`, `docs/13-testes.md`, schemas e migrations atuais.  
**Requirement:** ORG-08, MIG-02, MIG-03, MIG-07, EDG-06  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** manifest reproduzível identifica perda/duplicação e registra baseline verde/vermelho sem tocar mudanças locais não relacionadas; ≥4 testes estruturais passam.  
**Tests:** structural  
**Gate:** build  
**Commit:** `chore(migration): add baseline equivalence gate`

#### T2: Reduzir governança raiz ao escopo cross-boundary

**Status:** Complete

**What:** Reescrever mapa e instruções raiz para ownership, workflow conjunto e fontes locais, sem duplicar documentação de produto.  
**Where:** `/`  
**Depends on:** T1  
**Reuses:** `AGENTS.md`, `README.md` e invariantes aprovadas no design.  
**Requirement:** ORG-01, DOC-02, DOC-07  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** um agente localiza as sete fronteiras, sandbox e gates sem depender de descrição obsoleta; link check passa.  
**Tests:** structural  
**Gate:** build  
**Commit:** `docs(workspace): define boundary ownership map`

#### T3: Aplicar higiene de ambiente e segredos

**Status:** Complete

**What:** Substituir ambiente versionado real por exemplos seguros, bloquear defaults privilegiados e verificar ausência de segredo no build context.  
**Where:** `/`  
**Depends on:** T2  
**Reuses:** variáveis atuais sem copiar valores sensíveis.  
**Requirement:** SEC-01, SEC-06  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** `.env` real é ignorado, exemplos não contêm segredo, scan determinístico não encontra credenciais e configuração inválida falha; ≥4 checks passam.  
**Tests:** structural  
**Gate:** build  
**Commit:** `fix(config): remove versioned development secrets`

#### T4: Definir quality gates e CI transitório em matriz

**Status:** Complete

**What:** Criar pipeline raiz que invoca cada build standalone e políticas de lint, análise estática, coverage, dependency updates e resultados não executados.  
**Where:** `.github/workflows`  
**Depends on:** T3  
**Reuses:** workflow atual e convenção `-PwithIT`.  
**Requirement:** MIG-04, MIG-06, EDG-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** CI diferencia PASS/FAIL/NOT_RUN, inclui Kafka/Postgres/Redis ITs e não trata ausência de Docker como aprovação; workflow lint passa.  
**Tests:** structural  
**Gate:** build  
**Commit:** `ci(workspace): add transitional boundary matrix`

#### T5: Criar manifest de realocação e validação documental

**Status:** Complete

**What:** Mapear seção antiga para novo owner/path/action e criar validação de links, comandos, portas, variáveis, métricas e claims.  
**Where:** `scripts/docs`  
**Depends on:** T4  
**Reuses:** `docs/`, links e scripts existentes.  
**Requirement:** DOC-05, DOC-06, MIG-07, EDG-04  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** cada seção atual tem destino; referência inválida sintética faz o gate falhar; ≥6 testes estruturais passam.  
**Tests:** structural  
**Gate:** build  
**Commit:** `test(docs): add ownership and claim validation`

#### T6: Padronizar publicação local e composite opcional

**Status:** Complete

**What:** Criar convenção de repositório Maven temporário e composite explícito, mantendo GAV como declaração produtiva e substitution off no release gate.  
**Where:** `scripts/artifacts`  
**Depends on:** T5  
**Reuses:** publicação local de `feature-control` e Gradle composite oficial.  
**Requirement:** ORG-05, EDG-01  
**Tools:** filesystem/shell/web oficial quando necessário; Skill: `tlc-spec-driven`.  
**Done when:** consumer de prova resolve GAV local; ausência de artefato falha sem ler source de outra raiz; ≥3 checks passam.  
**Tests:** structural  
**Gate:** build  
**Commit:** `build(workspace): define artifact-only development flow`

### Phase 2 — `payment-contracts`

#### T7: Criar build standalone publicável de contratos

**Status:** Complete

**What:** Criar raiz Gradle independente com wrapper Java 21, módulos e publicação local sem ler o build raiz.  
**Where:** `payment-contracts`  
**Depends on:** T6  
**Reuses:** versões e plugins atuais, corrigindo sintaxe Gradle deprecada.  
**Requirement:** ORG-02, ORG-04  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** wrapper standalone compila e publica POM/sources/Javadoc; zero acesso ao root build; checks de publicação passam.  
**Tests:** structural  
**Gate:** build  
**Commit:** `build(contracts): create standalone publication root`

#### T8: Migrar modelo e schemas para o artefato de contrato

**Status:** Complete

**What:** Mover envelope, modelos, constantes, fontes Avro e geração para `payment-contract-model`, preservando contratos byte/JSON.  
**Where:** `payment-contracts/contract-model`  
**Depends on:** T7  
**Reuses:** `common` model/events/avro e seus cinco testes baseline.  
**Requirement:** ORG-06, PAY-12  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** cinco testes anteriores + testes de todos os event types/headers passam; nenhuma regra runtime entra no artefato.  
**Tests:** unit + contract  
**Gate:** quick  
**Commit:** `refactor(contracts): extract payment contract model`

**Adequacy review:** O contrato JSON preserva nomes, tipo numérico e valor de `BigDecimal`. A escala lexical (`125.50` versus `125.5`) não é parte do requisito; igualdade numérica foi confirmada pelo usuário após o gate expor esse gap de precisão.

**Correction evidence:** O modelo publicado ficou Java puro, sem Micronaut/Jakarta. O teste de ownership primeiro falhou sobre os builds e sete records acoplados e passou após a remoção; ele varre scripts de build, catálogo e fontes produtivas. Os 17 testes do modelo, inclusive o contrato JSON no consumer do serializer, passaram.

#### T9: Extrair adapter Avro/Apicurio com codec limitado

**Status:** Complete

**What:** Mover mapper/serde para adapter separado e substituir serializer por thread por codec thread-safe ou pool limitado mensurável.  
**Where:** `payment-contracts/contract-avro-apicurio`  
**Depends on:** T8  
**Reuses:** `AvroMapper`, `AvroSerde` e round-trips atuais.  
**Requirement:** ORG-06, PAY-09  
**Tools:** filesystem/shell/web oficial; Skill: `tlc-spec-driven`.  
**Done when:** concorrência virtual-thread não cria client por request; timeout/saturação e todos os round-trips são testados; ≥6 novos testes passam.  
**Tests:** unit + contract  
**Gate:** full  
**Commit:** `perf(contracts): bound avro registry codecs`

**Gate evidence:** 21 testes determinísticos passaram com `-PwithIT`: 10 no modelo e 11 no adapter. Integração com Registry externo ficou `NOT_RUN` por indisponibilidade de Docker; pool, timeout, falha e concorrência de virtual threads foram provados com codec injetável sem rede.

**Correction evidence:** `AvroSerde` preserva construtores Java explícitos, capacidade e timeout finitos e `AutoCloseable`, sem DI, configuração ou lifecycle de framework. A aplicação consumidora passa configuração e registra o fechamento. A regressão final passou 17 testes do modelo, 11 do adapter e 1 do fixture publicado (29 Java), além de 14 checks estruturais Python; Registry externo permaneceu `NOT_RUN` sem Docker.

#### T10: Implementar manifest e compatibilidade Avro

**Status:** Complete

**What:** Versionar mapa eventType/schema/tópico e gate `FULL_TRANSITIVE`, com dry run e rejeição de mudança incompatível.  
**Where:** `payment-contracts/schemas`  
**Depends on:** T9  
**Reuses:** schemas, `Topics`, `EventTypes` e regras oficiais do Apicurio.  
**Requirement:** PAY-12, EDG-03  
**Tools:** filesystem/shell/web oficial; Skill: `tlc-spec-driven`.  
**Done when:** schema compatível passa, mutação incompatível falha e major/coexistência são exigidos; ≥4 contract checks passam.  
**Tests:** contract  
**Gate:** full  
**Commit:** `feat(contracts): enforce transitive avro compatibility`

**Gate evidence:** 28 testes passaram; sete verificam histórico completo, mutações compatíveis/incompatíveis, major/coexistência, manifest e política. `checkSchemaCompatibility` executou dry run local sem mutar Registry.

#### T11: Provar consumo apenas pelo artefato publicado

**Status:** Complete

**What:** Criar consumer fixture que resolve os dois GAVs do repositório temporário com composite substitution desabilitada.  
**Where:** `payment-contracts/consumer-fixture`  
**Depends on:** T10  
**Reuses:** scripts de T6 e exemplos de payload atuais.  
**Requirement:** ORG-04, ORG-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** fixture compila, serializa e desserializa sem source cross-root; POM divergente/ausente faz o gate falhar; ≥3 testes passam.  
**Tests:** contract + structural  
**Gate:** build  
**Commit:** `test(contracts): add published artifact consumer fixture`

**Gate evidence:** quatro testes estruturais e um round-trip binário passaram. O fixture resolveu os dois GAVs publicados; POM divergente e repositório ausente falharam deterministicamente.

#### T12: Documentar ownership e decisões de contratos

**Status:** Complete

**What:** Criar README, AGENTS, política de compatibilidade, operação de registry e ADR local.  
**Where:** `payment-contracts/docs`  
**Depends on:** T11  
**Reuses:** documentação de eventos atual após validação de claims.  
**Requirement:** DOC-01, DOC-02, DOC-03, DOC-04  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** fonte de verdade, GAVs, evolução, publicação, proibições e gates são locais; docs validation passa.  
**Tests:** structural  
**Gate:** build  
**Commit:** `docs(contracts): add local architecture and adr`

**Gate evidence:** seis testes locais validaram 13 documentos, conteúdo mínimo, links, ownership e ADR; os nove testes documentais do workspace também passaram. O gate de equivalência permanece vermelho somente por adições/duplicações transitórias explícitas, sem perda baseline; o manifest não foi atualizado para esconder a divergência.

### Phase 3 — `/sandbox`

#### T13: Extrair infraestrutura mínima e rede externa

**Status:** Complete

**What:** Criar Compose do sandbox somente com Kafka, Redis, PostgreSQL e Registry, rede nomeada e volumes, sem fonte/build de aplicação.  
**Where:** `sandbox`  
**Depends on:** T12  
**Reuses:** serviços e init atuais, removendo `container_name`.  
**Requirement:** SBX-01, SBX-02, SBX-03  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** Compose minimal materializa rede externa e infra saudável sem app; config gate passa.  
**Tests:** structural + integration  
**Gate:** sandbox  
**Commit:** `refactor(sandbox): extract shared infrastructure compose`

**Gate evidence:** quatro testes estruturais passaram; o Compose materializou somente Kafka, Redis, PostgreSQL e Registry na rede nomeada `payment-sandbox`, sem build ou `container_name`. `docker compose up -d --wait` criou os três volumes nomeados e confirmou os quatro serviços healthy. A imagem Kafka legada inexistente foi substituída pela distribuição oficial Apache 3.9.2.

#### T14: Criar inicialização e smoke acionáveis

**Status:** Complete

**What:** Inicializar tópicos, regras/schemas e bancos idempotentemente e verificar capacidade real de cada dependência.  
**Where:** `sandbox/smoke`  
**Depends on:** T13  
**Reuses:** `kafka-init`, healthchecks e scripts atuais.  
**Requirement:** SBX-06, PAY-09  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** smoke diferencia cada dependência, falha com diagnóstico quando uma é parada e passa após recovery; ≥6 probes passam.  
**Tests:** integration  
**Gate:** sandbox  
**Commit:** `test(sandbox): add dependency readiness smoke`

**Gate evidence:** seis testes estruturais e nove probes de capacidade passaram. A inicialização repetida preservou tópicos, regra de compatibilidade, schema sintético e schema PostgreSQL; ao parar Redis, o smoke falhou nominalmente com dois diagnósticos e exit 1, depois passou após `start --wait`, sem remover volumes.

#### T15: Separar profiles de observabilidade e ferramentas

**Status:** Complete

**What:** Mover Jaeger/OTel, Prometheus, Grafana e UIs para profiles, preservando ownership dos artefatos de aplicação.  
**Where:** `sandbox/observability`  
**Depends on:** T14  
**Reuses:** `observability/` e configurações atuais.  
**Requirement:** SBX-02, SBX-05  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** minimal não sobe ferramentas; profiles completos carregam apenas manifests autorizados; queries básicas passam.  
**Tests:** integration + structural  
**Gate:** sandbox  
**Commit:** `refactor(sandbox): profile observability and tools`

**Gate evidence:** dez testes estruturais, cinco queries reais dos profiles e os nove probes mínimos passaram. O Compose mínimo não interpola secrets opcionais; `observability` materializa somente sete componentes comuns, `tools` somente Kafka UI, e o manifest de assets de aplicação permanece vazio e versionável.

#### T16: Detectar colisões de portas em todos os profiles

**What:** Criar validador que materializa combinações de profiles e falha para host bind duplicado ou variável ausente.  
**Where:** `sandbox/smoke/ports`  
**Depends on:** T15  
**Reuses:** Compose renderizado e conflito atual Apicurio/pilot.  
**Requirement:** SBX-04, DOC-06  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** conflito sintético na porta 8085 é detectado e configuração válida passa; ≥3 testes passam.  
**Tests:** structural  
**Gate:** sandbox  
**Commit:** `test(sandbox): reject compose port collisions`

#### T17: Fixar imagens, retenções e operações destrutivas seguras

**What:** Pin de versões/digests, configuração de volumes/retention e comandos de reset separados com confirmação.  
**Where:** `sandbox/config`  
**Depends on:** T16  
**Reuses:** imagens e volumes atuais.  
**Requirement:** SBX-01, SEC-07  
**Tools:** filesystem/shell/Docker/web oficial; Skill: `tlc-spec-driven`.  
**Done when:** images são reproduzíveis, nenhum reset roda no smoke/build e retention incoerente falha no validation; ≥4 checks passam.  
**Tests:** structural  
**Gate:** sandbox  
**Commit:** `chore(sandbox): pin images and safe data lifecycle`

#### T18: Documentar operação e ADR do sandbox

**What:** Criar quickstart, troubleshooting, profiles, rede, dados, manifest de dashboards, AGENTS e ADR.  
**Where:** `sandbox/docs`  
**Depends on:** T17  
**Reuses:** execução/operação e observabilidade atuais após validação.  
**Requirement:** SBX-01, DOC-01, DOC-02, DOC-03, DOC-04  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** sandbox pode ser operado sem docs de aplicação e todos os comandos/links passam no docs gate.  
**Tests:** structural  
**Gate:** build  
**Commit:** `docs(sandbox): add operations guide and adr`

### Phase 4 — `payment-core-mock`

#### T19: Relocar Core mock para build standalone

**What:** Mover source/config/testes para raiz própria e consumir contratos publicados, preservando tópicos e headers.  
**Where:** `payment-core-mock`  
**Depends on:** T18  
**Reuses:** consumer atual e `payment-contracts`.  
**Requirement:** ORG-02, ORG-03, ORG-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** build isolado não lê root/`common`, smoke Kafka passa e baseline de contrato é preservado.  
**Tests:** contract + integration  
**Gate:** full  
**Commit:** `refactor(core-mock): extract standalone application`

#### T20: Tornar simulação determinística e configuração validada

**What:** Extrair decisão pura por requestId/seed e validar percentuais, latência e combinações.  
**Where:** `payment-core-mock/src/main`  
**Depends on:** T19  
**Reuses:** regras de sucesso/falha atuais.  
**Requirement:** CAP-06, EDG-07  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** mesma entrada gera mesmo resultado; bounds inválidos recusam startup; ≥10 unit tests cobrem branches e limites.  
**Tests:** unit  
**Gate:** quick  
**Commit:** `fix(core-mock): make simulation deterministic`

#### T21: Provar redelivery e contratos do Core mock

**What:** Testar comando duplicado, resposta determinística, poison e propagação de correlação/trace sem efeitos divergentes.  
**Where:** `payment-core-mock/src/test`  
**Depends on:** T20  
**Reuses:** Testcontainers Kafka/Apicurio dos ITs atuais.  
**Requirement:** PAY-06, PAY-09  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** duplicatas produzem resultado equivalente e falhas não são confirmadas silenciosamente; ≥5 ITs passam.  
**Tests:** integration + contract  
**Gate:** full  
**Commit:** `test(core-mock): cover redelivery and contract failures`

#### T22: Criar imagem e Compose independentes do Core mock

**What:** Adicionar build multi-stage non-root e Compose somente da app conectado à rede externa.  
**Where:** `payment-core-mock/deploy`  
**Depends on:** T21  
**Reuses:** sandbox network e configuração Kafka atual.  
**Requirement:** ORG-03, SEC-07, SBX-03  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** imagem sem JAR versionado/root inicia na rede sandbox e Compose não cria infra; image checks passam.  
**Tests:** structural + integration  
**Gate:** build  
**Commit:** `build(core-mock): add isolated nonroot container`

#### T23: Classificar e documentar Core mock como não produtivo

**What:** Criar README/AGENTS/docs/ADR/CI locais com label `NON_PRODUCTION`, limites e perfis determinísticos.  
**Where:** `payment-core-mock/docs`  
**Depends on:** T22  
**Reuses:** documentação atual do Core validada.  
**Requirement:** DOC-01, DOC-02, DOC-03, DOC-04, EDG-07  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** label aparece em README, startup, imagem e CI; nenhuma claim de Core real permanece; docs/build passam.  
**Tests:** structural  
**Gate:** build  
**Commit:** `docs(core-mock): declare deterministic nonproduction scope`

### Phase 5 — `payment-sbus`

#### T24: Relocar SBUS para build standalone

**What:** Mover aplicação, migrations e testes para raiz própria consumindo contratos publicados sem alterar comportamento.  
**Where:** `payment-sbus`  
**Depends on:** T23  
**Reuses:** módulo SBUS e sete métodos de teste baseline.  
**Requirement:** ORG-02, ORG-03, ORG-05, MIG-02  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** build isolado, sete testes anteriores, Flyway checksums e IT Kafka/Postgres passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `refactor(sbus): extract standalone service`

#### T25: Fechar profile produtivo e superfícies do SBUS

**What:** Separar profiles, validar config, proteger endpoint interno e limitar management a health mínimo.  
**Where:** `payment-sbus/src/main`  
**Depends on:** T24  
**Reuses:** Micronaut Security/configuração produtiva atual.  
**Requirement:** SEC-01, SEC-03, SEC-04, SEC-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** startup inválido falha; rota interna exige identidade de serviço; matriz de rotas/profile tem ≥8 ITs novos.  
**Tests:** integration  
**Gate:** full  
**Commit:** `security(sbus): enforce production identity boundaries`

#### T26: Serializar finalização concorrente do estado

**What:** Adicionar migration append-only e update otimista/condicional para impedir terminais concorrentes.  
**Where:** `payment-sbus/src/main/resources/db/migration`  
**Depends on:** T25  
**Reuses:** migrations e `PaymentPersistenceService`; nenhuma migration aplicada é editada.  
**Requirement:** PAY-04, PAY-11, EDG-02  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** duas finalizações concorrentes escolhem um terminal e uma outbox coerente; ≥4 integration tests passam.  
**Tests:** integration  
**Gate:** full  
**Commit:** `fix(sbus): guard terminal state transitions`

#### T27: Substituir sleep por retry durável due-based

**What:** Persistir bytes/headers/`next_attempt_at` antes de confirmar consumo e publicar somente quando due.  
**Where:** `payment-sbus/src/main/java/com/example/payments/sbus/retry`  
**Depends on:** T26  
**Reuses:** outbox, backoff, headers e payload Avro serializado.  
**Requirement:** PAY-04, PAY-08  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** retry futuro nunca executa cedo nem bloqueia tráfego vivo; crash após schedule é recuperado; ≥8 testes novos passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(sbus): schedule kafka retries durably`

#### T28: Tornar DLQ recuperável até confirmação

**What:** Introduzir estados `DLQ_PENDING`/`DLQ_PUBLISHED`, backoff, lease e alerta sem `FAILED` terminal anterior ao ack.  
**Where:** `payment-sbus/src/main/java/com/example/payments/sbus/outbox`  
**Depends on:** T27  
**Reuses:** dispatcher, claim/reaper e producer atuais.  
**Requirement:** PAY-05, PAY-06, PAY-07  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** falha de DLQ permanece recuperável, crash send/mark republica com dedup e multi-instância não compartilha claim; ≥8 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(sbus): make dead letter delivery recoverable`

#### T29: Alinhar políticas de dependência e retenção

**What:** Tipar timeouts/retries/readiness e validar retenções de inbox, idempotência, estado, outbox e tópicos.  
**Where:** `payment-sbus/src/main/java/com/example/payments/sbus/config`  
**Depends on:** T28  
**Reuses:** configuração Kafka/JDBC/Redis e backoff atuais.  
**Requirement:** PAY-09, PAY-11, CAP-01  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** configurações incoerentes falham; matriz Kafka/DB/Redis/Registry prova estado recuperável; ≥6 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `reliability(sbus): enforce dependency and retention budgets`

#### T30: Completar pacote operacional e release do SBUS

**What:** Criar container/Compose/ops/README/AGENTS/ADRs/CI locais e supply-chain gates aplicáveis.  
**Where:** `payment-sbus/ops`  
**Depends on:** T29  
**Reuses:** dashboards, runbooks e Docker patterns aprovados.  
**Requirement:** ORG-03, SEC-07, SEC-08, DOC-01, DOC-02, DOC-03, DOC-04  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** imagem non-root e Compose app-only passam; runbooks cobrem retry/DLQ; CI inclui unit/IT/image/SBOM/scan/docs.  
**Tests:** structural + integration  
**Gate:** build  
**Commit:** `build(sbus): add independent production release package`

### Phase 6 — `payment-api`

#### T31: Relocar API para build standalone

**What:** Mover aplicação, load assets e testes para raiz própria consumindo contracts/feature-control publicados.  
**Where:** `payment-api`  
**Depends on:** T30  
**Reuses:** API atual e nove métodos de teste baseline.  
**Requirement:** ORG-02, ORG-03, ORG-05, MIG-02  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** build isolado e nove testes anteriores passam sem `project()` ou source cross-root.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `refactor(api): extract standalone service`

#### T32: Fechar autenticação e management produtivos da API

**What:** Remover token issuer do bean graph PRD, exigir JWT assimétrico/issuer/audience e restringir rotas/management.  
**Where:** `payment-api/src/main/java/com/example/payments/api/auth`  
**Depends on:** T31  
**Reuses:** config JWKS e security annotations atuais.  
**Requirement:** SEC-01, SEC-02, SEC-03, SEC-04, SEC-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** profile PRD inválido falha; dev route não existe em PRD; auth/role/audience/issuer/management têm ≥10 ITs novos.  
**Tests:** integration  
**Gate:** full  
**Commit:** `security(api): enforce production jwt and route policy`

#### T33: Implementar reserva idempotente com fingerprint

**What:** Associar atomicamente chave, requestId, fingerprint canônico e state machine, retornando replay ou conflito determinístico.  
**Where:** `payment-api/src/main/java/com/example/payments/api/idempotency`  
**Depends on:** T32  
**Reuses:** Redis status/idempotency store atual.  
**Requirement:** PAY-01, PAY-02, PAY-11  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** mesma chave/payload repete identidade, payload divergente retorna conflito e zero publish; TTL coerente; ≥10 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(api): fingerprint idempotent submissions`

#### T34: Recuperar atomicamente falha de publicação inicial

**What:** Coordenar reservation e Kafka ack, marcando `PUBLISHED` ou `PUBLISH_FAILED` retry-safe sem identidade órfã.  
**Where:** `payment-api/src/main/java/com/example/payments/api/service`  
**Depends on:** T33  
**Reuses:** `ApiPaymentService`, producer e coordinator atuais.  
**Requirement:** PAY-03, PAY-10  
**Tools:** filesystem/shell/Kafka; Skill: `tlc-spec-driven`.  
**Done when:** timeout/send failure/retry/crash windows preservam o mesmo requestId e não simulam processamento; ≥8 testes novos passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(api): recover initial publish failures`

#### T35: Limitar waiter, MDC e fallback de status

**What:** Garantir cleanup em todos os caminhos, budgets/shutdown e client SBUS com timeout/circuit/service identity.  
**Where:** `payment-api/src/main/java/com/example/payments/api/coordination`  
**Depends on:** T34  
**Reuses:** register-before-publish e read-after-register atuais.  
**Requirement:** PAY-09, PAY-10  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** result/timeout/interruption/shutdown removem waiter/MDC e fallback não excede budget; ≥10 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(api): bound response coordination lifecycle`

#### T36: Tornar consumo de respostas failure-safe

**What:** Aplicar codec limitado e retry/DLQ para decode/Redis failures sem confirmação silenciosa.  
**Where:** `payment-api/src/main/java/com/example/payments/api/kafka`  
**Depends on:** T35  
**Reuses:** artifact codec de T9 e response consumer atual.  
**Requirement:** PAY-06, PAY-09  
**Tools:** filesystem/shell/Kafka/Redis; Skill: `tlc-spec-driven`.  
**Done when:** poison e Redis outage são retry/DLQ observáveis, duplicata terminal é idempotente e serializer é bounded; ≥7 ITs passam.  
**Tests:** integration + contract  
**Gate:** full  
**Commit:** `fix(api): make response consumption recoverable`

#### T37: Completar admissão e pacote produtivo da API

**What:** Criar limites por recurso/tenant, falha fechada multi-instância e container/Compose/ops/docs/ADRs/CI locais.  
**Where:** `payment-api/ops`  
**Depends on:** T36  
**Reuses:** rate limiter atual, sandbox network, load e dashboards.  
**Requirement:** CAP-03, ORG-03, SEC-07, SEC-08, DOC-01, DOC-02, DOC-03, DOC-04  
**Tools:** filesystem/shell/Docker/Redis; Skill: `tlc-spec-driven`.  
**Done when:** Redis down não multiplica limite por instância; 429/202 são testados; imagem/Compose/CI/SBOM/scan/docs passam; ≥6 novos ITs.  
**Tests:** integration + structural  
**Gate:** build  
**Commit:** `build(api): add bounded production release package`

### Phase 7 — `async-redis-service`

#### T38: Relocar serviço Redis para build standalone

**What:** Mover aplicação e testes para raiz independente sem contratos Kafka/Postgres/common.  
**Where:** `async-redis-service`  
**Depends on:** T37  
**Reuses:** implementação e seis métodos de teste baseline.  
**Requirement:** ORG-02, ORG-03, ORG-07, MIG-02  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** build isolado preserva seis testes e não depende de outra raiz de código.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `refactor(async-redis): extract standalone service`

#### T39: Persistir status, idempotência e segurança na aceitação

**What:** Criar `PROCESSING` antes do enqueue, polling coerente, fingerprint/replay/conflict e AuthN obrigatória em PRD.  
**Where:** `async-redis-service/src/main/java/com/example/platform/asyncredis/api`  
**Depends on:** T38  
**Reuses:** controller, result keys e JSON atuais.  
**Requirement:** RED-01, RED-08  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** polling distingue missing/processing/terminal/expired; idempotência e auth têm happy/edge/error; ≥12 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(async-redis): secure idempotent job acceptance`

#### T40: Limitar pool de espera e admissão

**What:** Iniciar budget antes do borrow, configurar `maxWait/maxTotal` e retornar backpressure quando capacidade de BRPOP se esgotar.  
**Where:** `async-redis-service/src/main/java/com/example/platform/asyncredis/queue`  
**Depends on:** T39  
**Reuses:** pool dedicado, BRPOP e limiter atuais.  
**Requirement:** RED-02, CAP-03  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** pool saturado nunca bloqueia além do HTTP budget, não cresce ilimitado e retorna 429/202 conforme contrato; ≥8 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(async-redis): bound waiter pool and admission`

#### T41: Tornar workers únicos e reconectáveis

**What:** Usar identidade por instância/worker, um reclaim coordinator e loop de conexão com backoff/readiness.  
**Where:** `async-redis-service/src/main/java/com/example/platform/asyncredis/worker`  
**Depends on:** T40  
**Reuses:** consumer group, PEL e reclaim atuais.  
**Requirement:** RED-04, RED-05  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** duas instâncias não colidem, startup outage recupera worker e readiness reflete capacidade; ≥9 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(async-redis): recover unique stream workers`

#### T42: Liberar resultado e wakeup atomicamente antes do ACK

**What:** Implementar script/protocolo idempotente para result, status, LPUSH e TTL; ACK somente após sucesso integral.  
**Where:** `async-redis-service/src/main/java/com/example/platform/asyncredis/result`  
**Depends on:** T41  
**Reuses:** chaves de resultado/wakeup e ACK flow atuais.  
**Requirement:** RED-06  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** falhas em cada etapa não perdem resultado; redelivery é idempotente; TTL é coerente; ≥8 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(async-redis): atomically release job results`

#### T43: Corrigir poison, malformed e limite de entregas

**What:** Gravar DLQ com causa antes do ACK, corrigir off-by-one e nunca confirmar jobId/payload inválido silenciosamente.  
**Where:** `async-redis-service/src/main/java/com/example/platform/asyncredis/dlq`  
**Depends on:** T42  
**Reuses:** DLQ stream e delivery count atuais.  
**Requirement:** RED-07  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** exatamente `maxDeliveries` tentativas ocorrem e DLQ failure deixa item recuperável; malformed preserva motivo; ≥7 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(async-redis): make poison handling durable`

#### T44: Implementar retenção PEL-safe

**What:** Remover `MAXLEN ~`, exigir trim `ACKED` em Redis suportado ou sem auto-trim com alerta e runbook em versão anterior.  
**Where:** `async-redis-service/src/main/java/com/example/platform/asyncredis/retention`  
**Depends on:** T43  
**Reuses:** stream config e métricas atuais; sem inventar segurança de trim.  
**Requirement:** RED-03  
**Tools:** filesystem/shell/Redis/web oficial; Skill: `tlc-spec-driven`.  
**Done when:** payload pendente sobrevive à pressão de retenção, backlog alerta antes do orçamento e versão incompatível falha segura; ≥6 testes passam.  
**Tests:** integration  
**Gate:** full  
**Commit:** `fix(async-redis): preserve pending stream payloads`

#### T45: Completar pacote produtivo do serviço Redis

**What:** Criar container/Compose/ops/load/README/AGENTS/ADR/CI com profile guard, SBOM e scan.  
**Where:** `async-redis-service/ops`  
**Depends on:** T44  
**Reuses:** sandbox network, scripts Redis e Docker patterns aprovados.  
**Requirement:** ORG-03, SEC-01, SEC-07, SEC-08, DOC-01, DOC-02, DOC-03, DOC-04  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** produção só é declarada com gates RED completos; imagem/Compose/docs/CI/load smoke passam.  
**Tests:** structural + integration  
**Gate:** build  
**Commit:** `build(async-redis): add independent production package`

### Phase 8 — `feature-control` e exemplos

#### T46: Reagrupar biblioteca e exemplos em raiz standalone

**What:** Mover library, feature-demo e pilot-app para raiz própria, mantendo consumer fixture artifact-only e labels de exemplo.  
**Where:** `feature-control`  
**Depends on:** T45  
**Reuses:** publicação atual e 31 métodos de teste baseline.  
**Requirement:** ORG-02, ORG-04, ORG-05, MIG-02  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** build/publicação standalone preserva ≥31 testes e exemplos não são releases independentes.  
**Tests:** unit + integration + structural  
**Gate:** full  
**Commit:** `refactor(feature-control): regroup library and examples`

#### T47: Validar definições de flags por tipo

**What:** Aplicar bounds a nomes, tipos, percentuais, variantes/pesos, versions, salts e combinações antes de persistir/ativar.  
**Where:** `feature-control/library/src/main/java/com/example/platform/featurecontrol/model`  
**Depends on:** T46  
**Reuses:** `FlagDefinition`, `FlagType` e resolver atuais.  
**Requirement:** FTR-01  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** todas as combinações válidas/inválidas têm resultado determinístico; ≥15 unit tests novos passam.  
**Tests:** unit  
**Gate:** quick  
**Commit:** `fix(feature-control): validate flag definitions`

#### T48: Limitar stale e stampede do cache

**What:** Implementar LKG com `maxStale`, baseline/fail-closed, idade observável e single-flight/jitter na recarga.  
**Where:** `feature-control/library/src/main/java/com/example/platform/featurecontrol/source`  
**Depends on:** T47  
**Reuses:** `RedisFlagSource`, cache e `CompositeFlagSource`.  
**Requirement:** FTR-02  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** outage antes/depois de maxStale, recovery e concorrência produzem policy correta; ≥10 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(feature-control): bound stale flag decisions`

#### T49: Recuperar pubsub e medir convergência

**What:** Fechar conexões parciais, reconectar com jitter e medir/alertar convergência multi-instância.  
**Where:** `feature-control/library/src/main/java/com/example/platform/featurecontrol/pubsub`  
**Depends on:** T48  
**Reuses:** listener/invalidation atuais.  
**Requirement:** FTR-03  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** restart/outage não vaza conexão e duas instâncias convergem no limite ou alertam; ≥7 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(feature-control): recover pubsub convergence`

#### T50: Tornar mutações CAS e auditoria atômicas

**What:** Aplicar versão otimista a create/update/delete e gravar before/after/ator/resultado em Stream na mesma operação Lua.  
**Where:** `feature-control/library/src/main/java/com/example/platform/featurecontrol/store`  
**Depends on:** T49  
**Reuses:** store Redis, admin versioning e audit list atuais.  
**Requirement:** FTR-04  
**Tools:** filesystem/shell/Redis; Skill: `tlc-spec-driven`.  
**Done when:** delete stale retorna conflito, mutação sem audit não ocorre e actor é autenticado; ≥10 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(feature-control): atomically audit versioned mutations`

#### T51: Limitar cardinalidade e remover PII de decisões

**What:** Usar allowlist/buckets para tags e impedir user/bucketing key, nomes e variantes arbitrários em logs/métricas.  
**Where:** `feature-control/library/src/main/java/com/example/platform/featurecontrol/metrics`  
**Depends on:** T50  
**Reuses:** decision metrics e logs atuais.  
**Requirement:** FTR-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** alta cardinalidade sintética permanece bounded e scan de logs não encontra PII; ≥8 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(feature-control): bound decision telemetry`

#### T52: Certificar publicação e compatibilidade da biblioteca

**What:** Validar POM/sources/Javadoc/binary compatibility e consumer fixture sem project substitution.  
**Where:** `feature-control/consumer-fixture`  
**Depends on:** T51  
**Reuses:** publicação local existente e pilot adoption.  
**Requirement:** FTR-06, ORG-04, ORG-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** fixture usa somente GAV publicado, breaking API falha e artefatos esperados existem; ≥5 checks passam.  
**Tests:** contract + structural  
**Gate:** build  
**Commit:** `test(feature-control): certify published consumer contract`

#### T53: Fechar segurança e documentação dos exemplos

**What:** Restringir token/admin demo a dev/test, marcar exemplos `NON_PRODUCTION` e criar docs/AGENTS/ADR/CI da fronteira.  
**Where:** `feature-control/docs`  
**Depends on:** T52  
**Reuses:** docs de feature control/operação/adoção atuais.  
**Requirement:** SEC-01, SEC-02, SEC-04, DOC-01, DOC-02, DOC-03, DOC-04  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** rotas demo/admin não aparecem em PRD, exemplos têm label e documentação/CI/publication gates passam; ≥6 security ITs.  
**Tests:** integration + structural  
**Gate:** build  
**Commit:** `security(feature-control): isolate nonproduction examples`

### Phase 9 — Integração, capacidade e encerramento da migração

#### T54: Provar equivalência artifact-only ponta a ponta

**What:** Publicar bibliotecas localmente, construir apps sem substitution e executar fluxo Kafka/Redis pelos Composes independentes.  
**Where:** `scripts/e2e`  
**Depends on:** T53  
**Reuses:** smoke/ITs atuais e gates T1/T6.  
**Requirement:** MIG-04, MIG-05, ORG-08  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** build source-level compartilhado é detectado, contratos/status são equivalentes e inventário não perde item válido; ≥2 E2E completos passam.  
**Tests:** e2e + structural  
**Gate:** workspace  
**Commit:** `test(workspace): prove artifact-only flow equivalence`

#### T55: Executar matriz de falhas multi-instância do pagamento

**What:** Testar duas APIs/SBUS com duplicatas, crash send/mark, Core lento, poison e dependências indisponíveis.  
**Where:** `scripts/e2e/payment-failures`  
**Depends on:** T54  
**Reuses:** ITs API/SBUS, outbox/retry e Core determinístico.  
**Requirement:** PAY-05, PAY-06, PAY-07, PAY-08, PAY-09, CAP-05, CAP-06  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** toda aceitação termina ou fica recuperável, ordem por requestId é preservada e nenhum cenário perde silenciosamente; ≥10 cenários passam.  
**Tests:** e2e + failure  
**Gate:** workspace  
**Commit:** `test(payment): add multiinstance failure matrix`

#### T56: Executar matriz multi-instância do Redis async

**What:** Saturar pool, reiniciar Redis/workers, pressionar retention e validar status/PEL/ACK/DLQ com duas instâncias.  
**Where:** `scripts/e2e/async-redis-failures`  
**Depends on:** T55  
**Reuses:** ITs async e workers hardened.  
**Requirement:** RED-01, RED-02, RED-03, RED-04, RED-05, RED-06, RED-07, RED-08  
**Tools:** filesystem/shell/Docker/Redis; Skill: `tlc-spec-driven`.  
**Done when:** todos os oito ACs RED têm evidência observável; ≥10 cenários passam sem PEL/pool ilimitado.  
**Tests:** e2e + failure  
**Gate:** workspace  
**Commit:** `test(async-redis): add multiinstance recovery matrix`

#### T57: Criar gate de capacidade e perfis de Core

**What:** Versionar ambiente e executar steady/spike/soak/slowdown/recovery, com certified-target e constrained-core.  
**Where:** `load`  
**Depends on:** T56  
**Reuses:** scripts k6 e dashboards atuais, Core determinístico.  
**Requirement:** CAP-01, CAP-02, CAP-03, CAP-04, CAP-05, CAP-06, CAP-07, EDG-07  
**Tools:** filesystem/shell/Docker/k6; Skill: `tlc-spec-driven`.  
**Done when:** 10k/min por 15m e spike 20k/min/60s são automatizados; thresholds falham corretamente; relatório contém hardware/recursos/percentis/lag/backlog/GC/pools/DB.  
**Tests:** performance + e2e  
**Gate:** workspace  
**Commit:** `perf(workspace): add reproducible capacity gate`

#### T58: Validar observabilidade e referências de dashboards

**What:** Provar métricas/logs/traces/alertas de todos os estados críticos e rejeitar dashboard que referencia sinal removido.  
**Where:** `scripts/observability`  
**Depends on:** T57  
**Reuses:** manifests `ops` de cada app e sandbox observability.  
**Requirement:** SBX-05, DOC-03, EDG-04  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** request/correlation/causation/trace propagam, alertas críticos disparam sinteticamente e dashboard quebrado falha; ≥8 checks passam.  
**Tests:** integration + structural  
**Gate:** workspace  
**Commit:** `test(observability): validate owned signals and dashboards`

#### T59: Realocar conteúdo válido e remover legado obsoleto

**What:** Aplicar manifest documental, remover somente cópias/claims substituídos e eliminar root build/Docker/Compose/módulos antigos após equivalência.  
**Where:** `/`  
**Depends on:** T58  
**Reuses:** manifests T1/T5 e documentação local criada.  
**Requirement:** DOC-05, DOC-06, DOC-07, MIG-01, MIG-02, MIG-07, MIG-08  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** não existe segunda fonte, `common`/aggregator/Compose antigo saem apenas após gate verde, links/claims/inventário passam e remoções são listadas.  
**Tests:** structural + e2e  
**Gate:** workspace  
**Commit:** `refactor(workspace): remove verified legacy layout`

#### T60: Executar release gate e produzir evidência final

**What:** Rodar todos os builds/ITs/contracts/images/Compose/SBOM/scans/docs/E2E/performance e persistir relatório datado por fronteira.  
**Where:** `.specs/features/repository-segregation-production-hardening/validation-evidence`  
**Depends on:** T59  
**Reuses:** todos os gates anteriores; não substitui o Verifier independente do TLC.  
**Requirement:** SEC-08, MIG-03, MIG-04, MIG-05, MIG-06, MIG-08, CAP-04, CAP-07, EDG-05  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** cada gate possui comando/exit code/test count/evidência; NOT_RUN não vira PASS; `git diff --check` passa e todos os 77 requisitos estão em `In Tasks` ou `Implemented`.  
**Tests:** structural + full regression  
**Gate:** workspace  
**Commit:** `test(workspace): record production readiness evidence`

---

## Phase Execution Map

```text
Phase 1: T1 -> T2 -> T3 -> T4 -> T5 -> T6
Phase 2: T7 -> T8 -> T9 -> T10 -> T11 -> T12
Phase 3: T13 -> T14 -> T15 -> T16 -> T17 -> T18
Phase 4: T19 -> T20 -> T21 -> T22 -> T23
Phase 5: T24 -> T25 -> T26 -> T27 -> T28 -> T29 -> T30
Phase 6: T31 -> T32 -> T33 -> T34 -> T35 -> T36 -> T37
Phase 7: T38 -> T39 -> T40 -> T41 -> T42 -> T43 -> T44 -> T45
Phase 8: T46 -> T47 -> T48 -> T49 -> T50 -> T51 -> T52 -> T53
Phase 9: T54 -> T55 -> T56 -> T57 -> T58 -> T59 -> T60
```

## Task Granularity Check

| Tasks | Atomic deliverable | Status |
| --- | --- | --- |
| T1–T6 | um gate ou uma política de workspace por tarefa | ✅ Granular |
| T7–T12 | um build, artefato, contrato, fixture ou pacote documental por tarefa | ✅ Granular |
| T13–T18 | um concern do sandbox por tarefa | ✅ Granular |
| T19–T23 | um concern do Core mock por tarefa | ✅ Granular |
| T24–T30 | um concern de SBUS por tarefa | ✅ Granular |
| T31–T37 | um concern de API por tarefa | ✅ Granular |
| T38–T45 | um concern do serviço Redis por tarefa | ✅ Granular |
| T46–T53 | um concern da biblioteca/exemplos por tarefa | ✅ Granular |
| T54–T60 | um gate cross-boundary por tarefa | ✅ Granular |

## Diagram-Definition Cross-Check

| Task | Depends On | Diagram Shows | Status |
| --- | --- | --- | --- |
| T1 | None | start | ✅ |
| T2–T60 | tarefa imediatamente anterior | cadeia sequencial da respectiva fase; dependência cross-phase é o gate da fase anterior | ✅ |

Todas as dependências intra-phase foram comparadas individualmente pelo validador determinístico; não há dependência para fase futura.

## Test Co-location Validation

| Tasks | Code Layer | Matrix Requires | Task Says | Status |
| --- | --- | --- | --- | --- |
| T1–T7, T11–T18, T22–T23, T30, T37, T45–T46, T52–T53, T58–T60 | build/config/docs/ops | structural e integração quando há runtime | structural/integration no mesmo task | ✅ |
| T8–T10, T19–T21 | contrato/Core | unit + contract/integration | unit + contract/integration no mesmo task | ✅ |
| T24–T29 | DB/outbox/retry/security | unit/integration conforme layer | unit/integration no mesmo task | ✅ |
| T31–T36 | API/service/security/Kafka | unit/integration/contract | unit/integration/contract no mesmo task | ✅ |
| T38–T44 | Redis API/queue/worker | unit + integration | unit/integration no mesmo task | ✅ |
| T47–T51 | feature resolution/store/telemetry | unit + integration | unit/integration no mesmo task | ✅ |
| T54–T57 | cross-boundary/failure/performance | e2e/performance | e2e/performance no mesmo task | ✅ |

Nenhuma tarefa usa `Tests: none`; testes não são adiados para tarefa posterior.

---

## Requirement-to-Task Traceability

| Requirement group | Tasks |
| --- | --- |
| ORG-01..08 | T1, T2, T6–T13, T19, T22, T24, T30–T31, T37–T38, T45–T46, T52, T54 |
| SBX-01..06 | T13–T18, T22, T37 |
| SEC-01..08 | T3–T4, T17, T22, T25, T30, T32, T37, T39, T45, T53, T60 |
| PAY-01..12 | T8–T10, T14, T21, T25–T29, T32–T36, T55 |
| RED-01..08 | T39–T45, T56 |
| CAP-01..07 | T20, T29, T37, T40, T55, T57, T60 |
| FTR-01..06 | T47–T52 |
| DOC-01..07 | T2, T5, T12, T16, T18, T23, T30, T37, T45, T53, T58–T59 |
| MIG-01..08 | T1, T4–T5, T24, T31, T38, T46, T54, T59–T60 |
| EDG-01..07 | T1, T5–T6, T10, T16, T20, T26, T44, T57, T60 |
