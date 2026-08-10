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

**Status:** Complete

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

**Gate evidence:** treze testes estruturais passaram. O validador materializou quatro combinações reais; a fixture `8085` falhou citando porta e ambos os serviços, e a fixture sem variável obrigatória falhou com diagnóstico nominal.

#### T17: Fixar imagens, retenções e operações destrutivas seguras

**Status:** Complete

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

**Gate evidence:** dezessete testes passaram; doze imagens usam tag+digest, cinco volumes e retenções Kafka/Redis/Prometheus estão declarados e coerentes. Fixtures sem pin/retenção válida falharam, reset sem confirmação retornou exit 2 antes de Docker e nenhum smoke referencia reset destrutivo. Cinco queries e nove probes runtime continuaram verdes.

#### T18: Documentar operação e ADR do sandbox

**Status:** Complete

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

**Gate evidence:** `make verify` passou com 20 testes, nove probes mínimos e cinco queries de profiles; o gate local validou 11 documentos, links, comandos, ownership, ADR e manifest. Docs raiz passaram com 251 seções e governança com sete fronteiras. Equivalência permanece vermelha somente por adições/duplicações transitórias explícitas, sem alteração do baseline ou perda histórica.

### Phase 4 — `payment-core-mock`

#### T19: Relocar Core mock para build standalone

**Status:** Complete

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

**Gate evidence:** O wrapper da nova raiz executou quatro testes com `-PwithIT` contra Kafka e Apicurio reais. O smoke consumiu o comando Avro e validou tópico, chave, headers W3C, correlação, causação e payload da resposta; três checks estruturais provaram build/wrapper próprios, GAVs publicados e ausência de `project(...)`/`common`.

**Adequacy review:** ORG-02/03/05 e os critérios de T19 estão cobertos em `StandaloneBoundaryTest.java:23-42` e `CoreContractSmokeIT.java:94-113`. As asserções verificam os valores observáveis, não apenas chamadas. O gap inicial exigia o `traceparent` inteiro idêntico; com autorização do usuário, o contrato correto passou a exigir formato W3C e trace-id estável, permitindo novo span-id. O gate revelou que a instrumentação substituía também o trace-id; o response topic foi excluído do producer tracing para preservar o header encaminhado. Todos os testes mapeiam aos critérios de T19 e seguem `AGENTS.md` e a Test Coverage Matrix.

#### T20: Tornar simulação determinística e configuração validada

**Status:** Complete

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

**Gate evidence:** O gate unitário passou 21 testes, 18 deles derivados de T20. Sete cobrem determinismo, três outcomes, autorização e latência inclusiva; onze cobrem defaults, limites negativos/superiores, ordem min/max, soma percentual e o `@PostConstruct` que recusa a configuração no bean startup.

**Adequacy review:** A decisão repetível está provada em `CoreSimulationDecisionEngineTest.java:19-22`; branches e payload de decisão em `:27-45`; limites/valor fixo de latência em `:52-62`; autorização determinística em `:69-73`. Bounds e combinações são rejeitados em `CoreBehaviorPropertiesTest.java:31-89`, enquanto `:94-96` prova que a mesma validação está ligada ao lifecycle. Asserções verificam outcome/valor/exceção observável; todos os 18 testes mapeiam a CAP-06, EDG-07 ou aos critérios de T20 e seguem `AGENTS.md` e a Test Coverage Matrix.

#### T21: Provar redelivery e contratos do Core mock

**Status:** Complete

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

**Gate evidence:** O gate full passou 27 testes: 21 unitários/estruturais e seis ITs contra Kafka/Apicurio reais. Os cinco ITs novos provaram payload equivalente em duplicata, decline determinístico, poison Avro, failure profile e Registry outage. O smoke anterior voltou a provar contrato/correlação/trace. O teste de poison falhou primeiro porque o consumer processava o registro posterior; `SYNC_PER_RECORD` e o handler de exceção agora reposicionam o offset falho e impedem avanço silencioso.

**Adequacy review:** Duplicata equivalente está em `CoreRedeliveryIT.java:78-81`; decline e seus campos em `:100-105`; poison, failure e Registry outage mantêm offset `1` e zero resposta em `:126-127`, `:150-151` e `:175-176`, sustentados pela asserção estável de offset em `:193-195`. O contrato de tópico, headers W3C, trace-id, correlação, causação e payload permanece coberto em `CoreContractSmokeIT.java:94-113`. Cada asserção verifica estado/payload observável e mapeia PAY-06, PAY-09 ou aos critérios de T21; não há teste especulativo e as convenções vêm de `AGENTS.md` e da Test Coverage Matrix.

#### T22: Criar imagem e Compose independentes do Core mock

**Status:** Complete

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

**Gate evidence:** Sete checks estruturais e o build standalone com 21 testes passaram. O gate Docker construiu a imagem multi-stage a partir do repositório de contratos como contexto nomeado, iniciou a distribuição Gradle completa, aguardou `/health` saudável e confirmou em runtime UID/GID `10001`, root filesystem read-only, label `NON_PRODUCTION` e vínculo exclusivo à rede externa `payment-sandbox`. O Compose validado contém apenas a aplicação e o cleanup removeu somente o projeto efêmero, sem volumes, imagens ou infraestrutura compartilhada.

**Adequacy review:** O pin por tag+digest das duas bases e a ausência de instalação de pacote de healthcheck são exigidos em `deploy/test_container_package.py:15-23`; a distribuição completa e sem nome de JAR versionado em `:25-29`; o label da imagem em `:31-33`; ownership exclusivo do app e rede externa em `:35-43`; restrições de filesystem/capabilities em `:45-49`; e higiene do `.env.example` em `:51-53`. O gate executável `deploy/verify.sh:13-25` repete esses invariantes no contêiner saudável. As asserções verificam configuração e estado observável e mapeiam ORG-03, SEC-07, SBX-03 e os critérios de T22; o primeiro smoke detectou corretamente o `runnerJar` thin e motivou o uso de `installDist` antes da aprovação final.

#### T23: Classificar e documentar Core mock como não produtivo

**Status:** Complete

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

**Gate evidence:** O gate estrutural passou oito testes e validou 13 documentos, links, ownership, conteúdo obrigatório, ADR e classificação. O build standalone passou 21 testes e o gate full passou 27, incluindo os seis ITs Kafka/Apicurio. O smoke reconstruiu a imagem, iniciou saudável e confirmou o log `boundary.classification=NON_PRODUCTION`; sete checks de container e o Compose renderizado também passaram. Os gates centrais reportaram `root-governance: PASS` e `docs: PASS (251 sections)`. A equivalência permaneceu vermelha pelas relocações T1–T23 já explicitadas, inclusive a nova raiz Core; o baseline histórico não foi alterado para esconder a divergência.

**Adequacy review:** `scripts/validate_docs.py:12-25` exige o pacote documental proporcional; `:28-34` rejeita claims produtivas e dependência obsoleta; `:42-74` fixa conteúdo de propósito, contratos, configuração, operação, testes e performance; `:81-116` valida links, claims e ADR; `:119-130` exige `NON_PRODUCTION` em README, startup, imagem e CI. O sensor é provado por oito testes em `scripts/test_docs.py:20-56`. A garantia executável está no reporter imutável `NonProductionStartupReporter.java:11-20` e é observada pelo smoke em `deploy/verify.sh:21-26`; o workflow rotulado e artifact-only está em `.github/workflows/ci.yml:1-39`. As asserções verificam ausência/presença e estado runtime observável, mapeiam DOC-01/02/03/04, EDG-07 e cada critério de T23, sem promover o simulador nem inventar garantia de capacidade.

### Phase 5 — `payment-sbus`

#### T24: Relocar SBUS para build standalone

**Status:** Complete

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

**Gate evidence:** O build standalone consumiu `payment-contract-model:0.1.0` e `payment-contract-avro-apicurio:0.1.0` do repositório Maven local. O quick gate passou oito testes e o full gate passou nove, incluindo o fluxo real Kafka/PostgreSQL/Redis/Apicurio. O gate SHA-256 preservou byte a byte as migrations V1–V6. `root-governance` e `git diff --check` passaram. O setup antigo do IT não aplicava propriedades dinâmicas antes do DataSource; com autorização explícita, somente o harness passou a iniciar `ApplicationContext` com as propriedades dos containers, sem mudar assertions. O gate também revelou dois gaps de integração da extração: producer Kafka ambíguo e modelos publicados sem introspecção do consumer; ambos foram corrigidos no owner SBUS.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| ORG-02/03/05: build, wrapper e GAVs standalone | `StandaloneBoundaryTest.java:24-35` — `assertTrue(build.contains(...))`, `assertFalse(build.contains("project("))`, `assertTrue(Files.isRegularFile(...))` | build próprio sem source dependency | ✅ |
| MIG-02: migrations aplicadas imutáveis | `StandaloneBoundaryTest.java:40-53` — `assertEquals(expected, actual)` | checksums V1–V6 idênticos | ✅ |
| IT Kafka/PostgreSQL/Registry e comportamento anterior | `SbusFlowIT.java:103-125` — `assertTrue(findByRequestId(...).isPresent())`, `assertNotNull(coreCommand)`, `assertNotNull(completed)`, `assertEquals(COMPLETED, ...getStatus())` | requested persiste, outbox publica comando e resposta termina COMPLETED | ✅ |
| sete testes baseline sem redução | `RetryPublisherUnitTest.java:42-65`, `BackoffCalculatorUnitTest.java:16-24`, `SbusFlowIT.java:103-125` | retry/backoff/fluxo preservados | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `StandaloneBoundaryTest.java:24-35` — GAV/source/build assertions | ORG-02, ORG-03, ORG-05, Done-when T24 | ✅ |
| `StandaloneBoundaryTest.java:53` — checksum equality | MIG-02, Done-when T24 | ✅ |
| `SbusFlowIT.java:103-125` — persistência, comando, conclusão e terminal | Done-when T24 e baseline funcional | ✅ |
| `RetryPublisherUnitTest.java:42-65`, `BackoffCalculatorUnitTest.java:16-24` | sete métodos baseline de T24 | ✅ |

**Adequacy review:** cobertura suficiente e necessária. As asserções verificam estado, artefato e mensagens observáveis; nenhuma depende apenas de contagem de mocks. Os testes seguem o `AGENTS.md` raiz e a Test Coverage Matrix. Não há SPEC_DEVIATION.

#### T25: Fechar profile produtivo e superfícies do SBUS

**Status:** Complete

**What:** Separar profiles, validar config, proteger endpoint interno e limitar management a health mínimo.  
**Where:** `payment-sbus/src/main`  
**Depends on:** T24  
**Reuses:** Micronaut Security/configuração produtiva atual.  
**Requirement:** SEC-01, SEC-03, SEC-04, SEC-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** startup inválido falha; rota interna exige identidade de serviço; matriz de rotas/profile tem ≥8 ITs novos.  
**Tests:** integration  
**Gate:** full  
**Commit:** `fix(sbus): enforce production identity boundaries`

**Gate evidence:** O quick gate passou 14 testes. O full gate passou 25 testes, incluindo oito cenários HTTP/JWT com PostgreSQL, Kafka, Redis e Apicurio reais, dois ITs do profile produtivo e a regressão do fluxo. Produção exige JWKS RSA por HTTPS, issuer, audience, expiração, `not-before`, clock skew estrito de `0s`, endpoints obrigatórios e auto-registration Avro desabilitado. A configuração inválida falhou durante startup com causa específica. Somente liveness/readiness são anônimos; status interno exige `ROLE_PAYMENT_API`; health agregado e Prometheus exigem autenticação.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| SEC-01: startup produtivo inválido falha | `ProductionProfileIT.java:31-49` — `assertThrows(RuntimeException.class, ...start())` e `assertTrue(messages(...).contains("JWT audience is required in production"))` | contexto não inicia com audience inválida | ✅ |
| SEC-03: assimétrico, issuer/audience/exp/nbf e sem secret | `ProductionProfileIT.java:21-26` — assertions de JWKS/claims e `assertFalse(production.contains("secret:"))` | profile produtivo não aceita shared secret | ✅ |
| SEC-04: endpoint interno usa identidade de serviço | `SbusSecurityIT.java:65-83` — `401`, `401`, `403`, `404` para anônimo, malformed, role errada e `ROLE_PAYMENT_API` | somente identidade de serviço atravessa AuthN/AuthZ | ✅ |
| SEC-05: management mínimo anônimo | `SbusSecurityIT.java:88-103` — liveness/readiness `200`, health agregado/Prometheus `401` | apenas probes mínimos são públicos | ✅ |
| Config tipada/guardada | `ProductionSecurityGuardUnitTest.java:15-52` — `assertDoesNotThrow` válido e `assertThrows(ConfigurationException.class, ...)` para cinco inválidos | defaults e combinações inseguras são rejeitados | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `ProductionProfileIT.java:21-26,31-49` — profile e startup | SEC-01, SEC-03, Done-when T25 | ✅ |
| `SbusSecurityIT.java:65-83` — matriz de identidade | SEC-04, Done-when T25 | ✅ |
| `SbusSecurityIT.java:88-103` — matriz management | SEC-05, Done-when T25 | ✅ |
| `ProductionSecurityGuardUnitTest.java:15-52` — validações puras | SEC-01/03 e edge cases de configuração | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Cada rota modificada possui happy/denied/error observável, e cada branch do guard possui resultado exato. O HS256 aparece somente no harness test-only; o profile produtivo prova ausência de shared secret. Os testes seguem `AGENTS.md` e a Test Coverage Matrix. Não há SPEC_DEVIATION.

#### T26: Serializar finalização concorrente do estado

**Status:** Complete

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

**Gate evidence:** A migration append-only V7 adicionou `version` e o constraint de estados sem alterar V1–V6; o manifesto SHA-256 cobre V1–V7. O full gate passou 30 testes. Cinco ITs PostgreSQL executaram finalizações concorrentes reais, duplicata, conflito tardio e rollback. O `UPDATE ... WHERE status='PROCESSING' AND version=?` e a criação da outbox permanecem na mesma transação; somente o vencedor retorna `true` e persiste uma outbox.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| Dois terminais concorrentes escolhem um | `TerminalTransitionIT.java:83-98` — `assertEquals(1, winners)`, status/eventType/topic/payload conjugados | exatamente um vencedor e payload coerente | ✅ |
| Redelivery concorrente equivalente é idempotente | `TerminalTransitionIT.java:116-121` — um vencedor, `COMPLETED`, uma outbox | resultado terminal não duplica | ✅ |
| Duplicata sequencial não cria outbox | `TerminalTransitionIT.java:128-130` — `assertTrue(first)`, `assertFalse(second)`, count `1` | terminal sticky | ✅ |
| Conflito tardio não sobrescreve | `TerminalTransitionIT.java:137-145` — segundo `false`, `FAILED`, bytes originais e count `1` | primeiro terminal vence | ✅ |
| PAY-04: state + outbox atômicos | `TerminalTransitionIT.java:152-158` — falha de outbox lança, estado `PROCESSING`, count `0` | rollback total | ✅ |
| EDG-02: migration append-only | `StandaloneBoundaryTest.java:40-53` — `assertEquals(expected, actual)` sobre manifesto V1–V7 | migrations aplicadas imutáveis e V7 presente | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `TerminalTransitionIT.java:83-98,116-121` | PAY-11 e Done-when concorrente | ✅ |
| `TerminalTransitionIT.java:128-145` | PAY-11, terminal sticky/redelivery | ✅ |
| `TerminalTransitionIT.java:152-158` | PAY-04, atomicidade state+outbox | ✅ |
| `StandaloneBoundaryTest.java:53` | EDG-02, migration append-only | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Os testes afirmam winner, estado, tópico, tipo, bytes e cardinalidade, não somente execução de método. O rollback prova o limite transacional. Segue `AGENTS.md`, migrations append-only e a Test Coverage Matrix. Não há SPEC_DEVIATION.

#### T27: Substituir sleep por retry durável due-based

**Status:** Complete

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

**Gate evidence:** A migration append-only V8 adicionou identidade de deduplicação única à outbox; V1–V8 estão no manifesto SHA-256. O quick gate passou 23 testes e o full gate passou 44. Nove testes unitários do scheduler e cinco ITs PostgreSQL novos provaram cálculo due, tópico dedicado, bytes/chave/headers, falha de persistência, dedupe após crash/redelivery, claim somente quando due e ausência de `Thread.sleep`. Consumers principais retornam somente depois do commit da linha de retry; falha do banco propaga e impede o commit do offset.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| PAY-08: futuro nunca processa cedo | `DurableRetryIT.java:75-79` — row count `1`, due futuro e ausência no claim | retry permanece PENDING até due | ✅ |
| Bytes/key/headers e tentativa preservados | `DurableRetryIT.java:94-99` — key, retry topic, raw bytes, traceparent e attempt exatos | republicação não reconstrói payload | ✅ |
| Crash após schedule é recuperável | `DurableRetryIT.java:111-114` — first inserted, redelivery false, mesma identidade e count `1` | agendamento idempotente antes do offset | ✅ |
| Retry futuro não bloqueia tráfego vivo | `DurableRetryIT.java:127-129` — due claimed, future absent e `PENDING`; `:137-138` sem sleep | sem head-of-line blocking | ✅ |
| Backoff/due determinístico | `DurableRetrySchedulerUnitTest.java:60-65,153-157` — attempt/due base e cap máximo | `next_attempt_at` segue política | ✅ |
| Tópicos por origem | `DurableRetrySchedulerUnitTest.java:73-74` — core response retry topic | fluxo não mistura origens | ✅ |
| PAY-04: falha de persistência impede ack | `DurableRetrySchedulerUnitTest.java:135-139` — exception do PostgreSQL propaga | consumidor não retorna normalmente | ✅ |
| Headers e dedupe completos | `DurableRetrySchedulerUnitTest.java:101-107,112-126` — valores e coordenadas exatas | trace/reason/not-before e identidade preservados | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `DurableRetryIT.java:75-79,127-138` | PAY-08, future due e no-HOL | ✅ |
| `DurableRetryIT.java:94-114` | PAY-04/08, raw payload e crash recovery | ✅ |
| `DurableRetrySchedulerUnitTest.java:60-157` | Done-when T27 e edge cases listados | ✅ |
| `RetryPublisherUnitTest.java:44-64` | facade schedule/exhaustion preservada | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Os testes verificam dados persistidos, tempo, estado e claim reais; mocks ficam restritos ao cálculo/facade e afirmam argumentos completos. O teste de persistência falha explicitamente para provar que o offset não pode ser confirmado. Segue `AGENTS.md`, payload Avro imutável e a Test Coverage Matrix. Não há SPEC_DEVIATION.

#### T28: Tornar DLQ recuperável até confirmação

**Status:** Complete

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

**Gate evidence:** A migration append-only V9 adicionou `claim_token`, `dlq_started_at` e o índice parcial da fila `DLQ_PENDING`, preservando V1–V8 e ampliando o manifesto SHA-256 até V9. O full gate passou 62 testes. Nove ITs PostgreSQL reais provaram persistência anterior ao retorno do consumer, falha de broker com backoff, ack como única fronteira terminal, reclaim após crash send/mark com payload/headers exatos, claim concorrente, fencing de updates, exclusão do send com advisory lock de sessão, sinal contínuo entre pending/in-progress e exaustão sem novo `FAILED`. O send Kafka tem timeout total configurado e cancelamento testado. Seis unit tests do scheduler provaram identidade inclusive sem key, headers, bytes, deduplicação e propagação de falha; os testes de métricas/alerta provaram count/oldest age unconfirmed, threshold e severidade de `PaymentSbusRecoverableDlqStuck`. Os validadores de spec/tasks e `check_root_governance.py` passaram sem warnings. O gate de equivalência continua listando explicitamente o conjunto já conhecido de raízes/scripts extraídos e agora V9/código/testes T28 contra o baseline histórico pré-migração; esse baseline não foi alterado para mascarar a divergência intencional.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| DLQ é durável antes do commit do offset | `RecoverableDeadLetterIT.java:101-114` — row `DLQ_PENDING`, tópico, bytes e trace exatos | retorno normal só ocorre após persistência recuperável | ✅ |
| PAY-07: falha de DLQ continua recuperável e alertável | `RecoverableDeadLetterIT.java:117-133` — publish falha, status permanece `DLQ_PENDING`, attempts/error/backoff e count `1`; `SbusMetricsUnitTest.java:14-26` e `SbusAlertContractTest.java:12-20` — count/age e alerta crítico `>300s` por `5m` | nenhum terminal silencioso e backlog travado gera sinal acionável | ✅ |
| Ack é a única fronteira terminal | `RecoverableDeadLetterIT.java:136-149` — somente publish bem-sucedido produz `DLQ_PUBLISHED` com mesmos bytes | terminal após confirmação do broker | ✅ |
| PAY-06: crash send/mark republica mesma identidade | `RecoverableDeadLetterIT.java:152-182` — mesma key, ambos payloads e trace/correlation/causation/stage exatos | janela at-least-once explícita e compatível com dedupe downstream | ✅ |
| PAY-05: duas instâncias não compartilham claim | `RecoverableDeadLetterIT.java:185-201` — duas transações concorrentes somam uma única row | `FOR UPDATE SKIP LOCKED` impede claim inicial concorrente | ✅ |
| PAY-05: lease expirada possui ownership/fencing | `RecoverableDeadLetterIT.java:204-222` — token A != B; success A afeta `0`, failure A retorna `STALE_CLAIM`, token/estado B intactos e somente B conclui | owner tardio não sobrescreve owner atual nem terminal | ✅ |
| PAY-05: publicação não sobrepõe após reclaim | `RecoverableDeadLetterIT.java:225-257` — send A bloqueado; reaper+B claim; B não chama publisher; após A liberar, recovery B publica | advisory lock de sessão cobre todo o I/O fora da transação | ✅ |
| PAY-07: alerta não zera durante claim | `RecoverableDeadLetterIT.java:260-275` — count permanece `1`, `dlq_started_at` não muda e oldest age não diminui em `IN_PROGRESS` | `for:5m` observa trabalho até ack | ✅ |
| Publish Kafka é limitado | `KafkaPublisherUnitTest.java:23-37` — get usa `10ms`, timeout propaga e delivery é cancelada | lock é liberado mesmo com broker bloqueado | ✅ |
| Exaustão não cria `FAILED` terminal | `RecoverableDeadLetterIT.java:278-294` — retry normal, depois `DLQ_PENDING`/DLQ e nunca `FAILED` | trabalho permanece recuperável até ack | ✅ |
| Identidade, payload e headers são determinísticos | `DurableDeadLetterSchedulerUnitTest.java:41-115` — bytes, coordenadas, stage, trace, reason, duplicate result e fallback sem key | redelivery não duplica schedule nem reconstrói payload | ✅ |
| Falha do banco impede retorno normal | `DurableDeadLetterSchedulerUnitTest.java:84-91` — exception de storage propaga | consumer não pode confirmar offset sem DLQ durável | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `RecoverableDeadLetterIT.java:101-149` | PAY-07 e fronteira persistência/backoff/ack | ✅ |
| `RecoverableDeadLetterIT.java:152-257` | PAY-05/06, crash, concorrência, fencing e lock durante send | ✅ |
| `RecoverableDeadLetterIT.java:260-294` | PAY-07, sinal contínuo e ausência de terminal silencioso | ✅ |
| `KafkaPublisherUnitTest.java:23-37` | PAY-05, tempo máximo de posse do lock | ✅ |
| `DurableDeadLetterSchedulerUnitTest.java:41-115` | Done-when T28, dedupe/headers/falha/key ausente | ✅ |
| `SbusMetricsUnitTest.java:14-26`; `SbusAlertContractTest.java:12-20` | PAY-07, idade e regra de alerta owned | ✅ |

**Adequacy review:** cobertura suficiente e necessária após corrigir os findings dos dois ciclos do verifier. Os ITs verificam estado persistido, cardinalidade, token de ownership, fencing de sucesso/falha tardios e exclusão real durante o send: o segundo publisher é observado com zero interações enquanto o primeiro mantém o lock de sessão. O timeout limitado garante liberação mesmo com broker bloqueado. Backoff, bytes e headers completos são afirmados, não apenas chamadas. O cenário de crash assume entrega at-least-once e comprova a republicação com a mesma identidade; a equivalência downstream já é coberta em T21, enquanto o gate da API permanece em sua tarefa proprietária. `dlq_started_at` não é resetado por claim/reaper, e métricas `unconfirmed` incluem `DLQ_PENDING` e `IN_PROGRESS`, sustentando a regra owned até ack. O enum legado `FAILED` continua legível para compatibilidade histórica, mas nenhum caminho T28 o grava. Não há teste especulativo, enfraquecimento de gate ou SPEC_DEVIATION.

**Independent verifier:** PASS após dois ciclos de findings corrigidos. O verifier executou novamente 62/62 testes, validadores spec/tasks, root governance e `git diff --check`; confirmou lock de sessão durante todo o broker I/O, fencing de updates, recovery, dados exatos, sinal contínuo até ack, alerta owned, V1–V8 byte-identical e manifesto V1–V9 válido. Sensor discriminatório `NOT_RUN` conforme regra read-only sobre worktree suja; nenhuma evidência foi inferida desse sensor.

#### T29: Alinhar políticas de dependência e retenção

**Status:** Complete

**What:** Tipar timeouts/retries/readiness e validar retenções de inbox, idempotência, estado, outbox e tópicos.  
**Where:** `payment-sbus/src/main/java/com/example/payments/sbus/config`  
**Depends on:** T28  
**Reuses:** configuração Kafka/JDBC/Redis e backoff atuais.  
**Requirement:** PAY-09, PAY-11, CAP-01  
**Tools:** filesystem/shell/Docker; Skill: `tlc-spec-driven`.  
**Done when:** configurações incoerentes falham; matriz Kafka/DB/Redis/Registry prova estado recuperável; ≥6 testes passam.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `fix(sbus): enforce dependency and retention budgets`

**Gate evidence:** O full gate standalone passou 74 testes. A configuração tipada define timeout, tentativas, readiness obrigatória e estado recuperável para Kafka, PostgreSQL, Redis e Schema Registry. Kafka usa o budget no producer; Redis usa timeout finito e falha fechada, sem multiplicar o limite por instância. Falha de capacidade do codec continua retryable no registro Kafka. O guard de startup impede idempotência menor que a janela de replay, estado menor que a deduplicação e outbox publicada menor que a janela máxima de redelivery.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| PAY-09: matriz Kafka/DB/Redis/Registry com readiness | `DependencyPoliciesUnitTest.java:25-29` — quatro `assertEquals(...)` e `assertTrue(...requiredForReadiness)` | cada falha mantém `CLAIMED_OUTBOX` ou `KAFKA_RECORD` e derruba readiness | ✅ |
| Budgets inválidos falham | `DependencyPoliciesUnitTest.java:37,45,53` — `assertThrows(ConfigurationException.class, ...)` | timeout não positivo, zero tentativa ou readiness opcional são recusados | ✅ |
| PAY-11: retenção cobre replay | `RetentionPolicyGuardUnitTest.java:15-34` — `assertDoesNotThrow(...)` válido e três `assertThrows(...)` incoerentes | dedup, estado e outbox não expiram antes de suas garantias | ✅ |
| Config incoerente impede startup | `DependencyPolicyIT.java:32-36` — `assertThrows(RuntimeException.class, ...)` e mensagem exata | contexto não inicia com dedup menor que retenção Kafka | ✅ |
| Redis indisponível falha fechado | `RedisRateLimiterUnitTest.java:19` — `assertThrows(IllegalStateException.class, limiter::tryAcquire)` | nenhuma capacidade local multiplicável por instância | ✅ |
| Registry indisponível permanece retryable | `SimulationMessageHandlerUnitTest.java:26-29` — `assertThrows(...)` e `assertSame(unavailable, actual)` | registro Kafka permanece sem confirmação para retry | ✅ |
| Kafka/DLQ indisponível mantém estado recuperável | `RecoverableDeadLetterIT.java:101-149` — estado `DLQ_PENDING`, tentativas/idade e ausência de terminal silencioso | outbox permanece recuperável até ack | ✅ |
| PostgreSQL indisponível impede confirmação | `DurableRetrySchedulerUnitTest.java:135-139` — exception de persistência exata propaga | consumer não retorna normalmente nem confirma offset | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `DependencyPoliciesUnitTest.java:25-29,37,45,53` | PAY-09 e Done-when T29 | ✅ |
| `RetentionPolicyGuardUnitTest.java:15-34` | PAY-11 e Done-when T29 | ✅ |
| `DependencyPolicyIT.java:19-26,32-36` | PAY-09/PAY-11, binding e startup | ✅ |
| `RedisRateLimiterUnitTest.java:19` | PAY-09/CAP-01, falha fechada sob Redis outage | ✅ |
| `SimulationMessageHandlerUnitTest.java:26-29` | PAY-09, Registry retryable | ✅ |
| `RecoverableDeadLetterIT.java:101-149`; `DurableRetrySchedulerUnitTest.java:135-139` | PAY-09, estados recuperáveis Kafka/DB | ✅ |

**Adequacy review:** cobertura suficiente e necessária. A matriz combina políticas tipadas com outcomes reais já preservados nos gates de Kafka e PostgreSQL; os novos testes fecham Redis fail-closed, Registry retryable e todas as relações de retenção. As asserções verificam exceção, estado e valor observáveis, não contagens de mocks. O teste especulativo de `lease > publish-timeout` foi removido porque T28 usa lock de sessão para exclusão e permite lease curto para recovery. Segue `AGENTS.md` e a Test Coverage Matrix. Não há SPEC_DEVIATION.

#### T30: Completar pacote operacional e release do SBUS

**Status:** Complete

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

**Gate evidence:** `./gradlew build --no-daemon` passou 42 testes rápidos e gerou runner JAR/distribuições. O full gate imediatamente anterior da mesma árvore passou 74 testes. `scripts/verify-docs.sh` passou três testes e validou 17 documentos; `deploy/verify.sh --structural` passou oito testes e `docker compose config -q`. A imagem local `payment-sbus:t30` foi construída pelo Dockerfile standalone com o repositório Maven publicado como contexto; inspeção confirmou `10001:10001`, healthcheck de liveness e label `payment-sbus`. O smoke de runtime ficou `NOT_RUN` porque o daemon Docker deixou de estar disponível antes da verificação da rede externa; build e inspeção já haviam concluído com sucesso.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| SEC-07: bases pinadas, runtime non-root e health sem pacote | `deploy/test_release_package.py:14-27` — `assertTrue(...@sha256...)`, `assertIn("USER 10001:10001", ...)`, `assertNotRegex(...apk|apt-get...)` | imagem mínima, pinada e non-root | ✅ |
| Compose app-only na rede externa | `deploy/test_release_package.py:29-35` — `assertEqual(["sbus"], ...)`, `assertIn("external: true", ...)`, ausência de seis serviços infra | somente SBUS; sandbox mantém ownership da infra | ✅ |
| Filesystem/capabilities restritos | `deploy/test_release_package.py:37-41` — assertions de read-only, no-new-privileges, `cap_drop` e user | runtime sem privilégio e filesystem somente leitura | ✅ |
| SEC-08: CI unit/IT/image/SBOM/scan/docs | `deploy/test_release_package.py:43-49` — cada marker obrigatório e threshold HIGH/CRITICAL com exit 1 | pipeline bloqueante cobre supply chain e gates locais | ✅ |
| Nenhum segredo no env versionado | `deploy/test_release_package.py:51-53` — `assertNotRegex(...password|secret|token...)` | `.env.example` não atribui credencial | ✅ |
| Retry/DLQ/rollback possuem runbooks owned | `deploy/test_release_package.py:55-59` — três `assertIn(...md)` | índice operacional aponta para os três procedimentos | ✅ |
| DOC-01..04: pacote, links, claims e ADR | `scripts/test_docs.py:18-34` — resultado vazio, missing package e broken link detectados | pacote proporcional completo e validator discriminante | ✅ |
| Imagem construída possui identidade observável | inspeção local — `10001:10001`, healthcheck `/health/liveness`, label `payment-sbus` | artefato real corresponde ao contrato estrutural | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `deploy/test_release_package.py:14-27` | SEC-07, Done-when imagem | ✅ |
| `deploy/test_release_package.py:29-41` | ORG-03/SEC-07, Done-when Compose app-only | ✅ |
| `deploy/test_release_package.py:43-49` | SEC-08, Done-when CI | ✅ |
| `deploy/test_release_package.py:51-59` | SEC-07 e Done-when runbooks | ✅ |
| `scripts/test_docs.py:18-34` | DOC-01..04, pacote e validação | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Os testes estruturais verificam valores e superfícies observáveis, e os testes negativos provam que pacote ausente e link quebrado falham. O build real da imagem complementa a inspeção de texto. A execução remota da CI e o smoke dependente do sandbox não foram presumidos; o último está registrado como `NOT_RUN` conforme EDG-05. Segue `AGENTS.md`, a Test Coverage Matrix e o owner local criado nesta tarefa. Não há SPEC_DEVIATION.

### Phase 6 — `payment-api`

#### T31: Relocar API para build standalone

**Status:** Complete

**What:** Mover aplicação, load assets e testes para raiz própria consumindo contracts/feature-control publicados.  
**Where:** `payment-api`  
**Depends on:** T30  
**Reuses:** API atual e nove métodos de teste baseline.  
**Requirement:** ORG-02, ORG-03, ORG-05, MIG-02  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** build isolado e nove testes anteriores passam sem `project()` ou source cross-root.  
**Tests:** unit + integration  
**Gate:** full  
**Commit:** `refactor(api): extract standalone service` (`7de39fb`) + `fix(api): wire contract codec and result serde for standalone gate`

**Gate evidence:** A raiz `payment-api` foi criada em `7de39fb` (fora do ciclo atômico desta skill) com build/wrapper próprios consumindo `payment-contract-model`, `payment-contract-avro-apicurio` e `feature-control` publicados localmente. Nesta retomada, `feature-control` foi publicado no repositório local (`./gradlew :feature-control:publishMavenPublicationToLocalBuildRepository`, fora do commit) e o full gate (`./gradlew test -PwithIT --no-daemon`, com `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exportados, iguais ao padrão do `ci.yml`) foi executado de fato pela primeira vez contra Kafka/Redis/Apicurio reais via Testcontainers. Três gaps reais e pré-existentes (idênticos na raiz `api-service` original, confirmados rodando `:api-service:test -PwithIT` antes de qualquer alteração) impediam um verde honesto e foram corrigidos: (1) `PaymentResponseConsumer`/`ApiPaymentService` injetavam `AvroSerde` sem nenhum `@Factory` o prover — `payment-api/src/main/java/com/example/payments/api/config/ContractCodecFactory.java` foi adicionado replicando o padrão já usado em `payment-sbus`/`payment-core-mock`; (2) `ApiFlowIT` usava `@MicronautTest` + `TestPropertyProvider`, cujas propriedades não sobrepõem, neste setup, chaves do `application.yml` que já carregam `${ENV_VAR:default}` (confirmado: `kafka.bootstrap.servers` continuava resolvendo para `localhost:9092` mesmo com a propriedade de teste definida) — o teste foi reescrito para o padrão já comprovado em `SbusSecurityIT`/`SbusFlowIT` (`ApplicationContext.run(EmbeddedServer.class, properties())`), e passou a autenticar com `X-API-Key` de fato em vez de depender de um `payment.security.enabled=false` que nunca chegava a desabilitar o filtro; (3) `SimulationResult`/`Fees`/`Settlement` (modelo de `payment-contracts`, deliberadamente livre de framework) não tinham metadata Serde, quebrando a serialização de `StatusResponse`/`StatusEntry`/`SbusStatusResponse` — `@SerdeImport` para os três tipos foi adicionado em `payment-api/src/main/java/com/example/payments/api/Application.java`, sem tocar o artefato publicado. Após as correções, `./gradlew test -PwithIT --no-daemon` passou **10/10 testes** (os nove baseline + `StandaloneBoundaryTest`, nova para esta fronteira), incluindo `ApiFlowIT` fim a fim contra Kafka/Redis/Apicurio reais.

**Adequacy review:** Os três gaps eram pré-existentes e não introduzidos por esta sessão — confirmados reproduzindo a mesma falha na raiz `api-service` do monorepo antes de qualquer mudança local. Nenhuma asserção foi enfraquecida ou removida para o gate passar: `ApiFlowIT` continua verificando o mesmo contrato observável (202 na submissão, `COMPLETED` e `authorizationCode` corretos após o evento Kafka), apenas com um client/contexto que efetivamente aplica a config de teste e com autenticação real via `X-API-Key`. `ContractCodecFactory` e `@SerdeImport` replicam exatamente os padrões já auditados e aprovados em T9/T19/T24 para o mesmo problema estrutural (contratos livres de framework consumidos por apps Micronaut). Escopo T32–T37 (auth produtiva completa, idempotência com fingerprint, recuperação de publish, DLQ de resposta, admissão) permanece explicitamente em aberto: o código já presente em `7de39fb` cobre parcialmente essas áreas de forma não gateada tarefa-a-tarefa, e será auditado/completado tarefa por tarefa a partir daqui, não retroativamente aqui. Não há SPEC_DEVIATION em T31.

#### T32: Fechar autenticação e management produtivos da API

**Status:** Complete

**What:** Remover token issuer do bean graph PRD, exigir JWT assimétrico/issuer/audience e restringir rotas/management.  
**Where:** `payment-api/src/main/java/com/example/payments/api/auth`  
**Depends on:** T31  
**Reuses:** config JWKS e security annotations atuais.  
**Requirement:** SEC-01, SEC-02, SEC-03, SEC-04, SEC-05  
**Tools:** filesystem/shell; Skill: `tlc-spec-driven`.  
**Done when:** profile PRD inválido falha; dev route não existe em PRD; auth/role/audience/issuer/management têm ≥10 ITs novos.  
**Tests:** integration  
**Gate:** full  
**Commit:** `fix(api): enforce production jwt and route policy` (template said `security(...)`, not an allowed Conventional Commit type per `check_commit.py`; `fix` matches T25's precedent for the identical requirement set)

**Gate evidence:** `DevTokenController` ganhou `@Requires(notEnv = "prod")`, excluindo bean e rota do bean graph produtivo (não apenas rejeitando a chamada). O segredo HS256 (`token.jwt.signatures.secret.generator`), antes declarado incondicionalmente no `application.yml` base, foi movido para `application-dev.yml` — produção nunca declara `secret:`, eliminando um validador simétrico paralelo que aceitaria um JWT forjado mesmo com JWKS assimétrico configurado. `application-prod.yml` passou a exigir `jwks` RSA, `issuer`, `audience`, `expiration: true`, `not-before: true` e `payment.security.clock-skew: 0s`. `ProductionSecurityGuard` (novo, `payment-api/src/main/java/com/example/payments/api/config/ProductionSecurityGuard.java`) falha o startup em produção quando JWKS/issuer não são HTTPS válidos, audience está ausente, clock-skew ≠ 0s, `payment.security.enabled` é falso, ou a lista de API keys está vazia/em branco/usa o default de desenvolvimento (`dev-key-change-me`). `intercept-url-map` foi reescrito com padrões explícitos por rota (`/health/liveness`, `/health/readiness`, `/auth/**`, `/admin/**`, `/payment-simulations/**`, `/v0/payment-simulations/**`, catch-all `isAuthenticated()`), e `endpoints.all.enabled` passou de `true`/`sensitive:false` para `false`, com apenas `health` (`details-visible: NEVER`) e `prometheus` (`sensitive: true`) habilitados — mesma política já auditada em T25/SBUS. O full gate (`./gradlew test -PwithIT --no-daemon`, `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exportados) passou **34/34 testes**: os 10 de T31 mais 24 novos (`ApiSecurityIT` com 12, `ProductionProfileIT` com 3, `ProductionSecurityGuardUnitTest` com 8, uma unidade a mais que o Done-when pede) sem redução de nenhum teste anterior.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| SEC-01: startup produtivo inválido falha | `ProductionProfileIT.java:80-92` — `assertThrows(RuntimeException.class, ...start())` e mensagem `"JWT audience is required in production"` | contexto não inicia com audience inválida | ✅ |
| SEC-01: defaults de desenvolvimento recusados | `ProductionSecurityGuardUnitTest.java:60-73` — `assertThrows` para API key ausente, vazia, em branco e default `dev-key-change-me` | nenhum default de dev sobrevive ao guard produtivo | ✅ |
| SEC-02: dev token issuer fora do bean graph/rota em PRD | `ProductionProfileIT.java:96-101` — `assertEquals(HttpStatus.NOT_FOUND, ...)` sobre POST `/auth/token` em contexto `prod` real; `ApiSecurityIT.java:99-103` prova a mesma rota existe (200) fora de produção | rota inexistente em produção, existente fora dela — não apenas bloqueada | ✅ |
| SEC-03: assimétrico, issuer/audience/exp/nbf e sem secret compartilhado | `ProductionProfileIT.java:62-70` — assertions sobre `application-prod.yml` (`jwks:`, `issuer:`, `audience:`, `expiration: true`, `not-before: true`, ausência de `secret:`) | profile produtivo não aceita HS256 nem claims incompletas | ✅ |
| SEC-04: admin exige `ROLE_ADMIN`; business endpoint documentado/testado | `ApiSecurityIT.java:64-79` — `401`/`401`/`403`/`204` para anônimo, token malformado, role errada e `ROLE_ADMIN`; `:81-84` — `401` sem `X-API-Key` | matriz de identidade do endpoint admin e do endpoint de negócio provada | ✅ |
| SEC-05: management mínimo anônimo, resto autenticado | `ApiSecurityIT.java:106-124` — liveness/readiness `200` anônimo, `/health` e `/prometheus` `401`, `/beans` e `/env` `404` (endpoint desabilitado) | apenas probes mínimos são públicos; endpoints não listados nem existem | ✅ |
| Config tipada/guardada (demais combinações) | `ProductionSecurityGuardUnitTest.java:20-77` — `assertDoesNotThrow` válido e seis `assertThrows(ConfigurationException.class, ...)` para JWKS inseguro, issuer inseguro, clock-skew ≠ 0, auth de API key desabilitada, lista vazia e chave em branco | cada branch do guard tem resultado exato | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `ProductionProfileIT.java:62-92` — texto do profile e falha de startup | SEC-01, SEC-03, Done-when T32 | ✅ |
| `ProductionProfileIT.java:96-101`; `ApiSecurityIT.java:99-103` — ausência/presença da rota dev | SEC-02, Done-when T32 | ✅ |
| `ApiSecurityIT.java:64-84` — matriz admin e business endpoint | SEC-04, Done-when T32 | ✅ |
| `ApiSecurityIT.java:87-91,106-124` — v0 self-enforced e matriz management | SEC-04/SEC-05 | ✅ |
| `ProductionSecurityGuardUnitTest.java:20-77` — validações puras | SEC-01/03 e edge cases do guard | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Cada rota tem happy/denied/error observável (status HTTP exato, não apenas "não lança exceção"), e cada branch do guard tem resultado exato via `ConfigurationException`. O segredo HS256 nunca é observável em `application-prod.yml` nem ativo num contexto `prod` real — `ProductionProfileIT.devTokenRouteAbsentInProduction` prova isso executando um `EmbeddedServer` produtivo de verdade contra Kafka/Redis/Apicurio reais, não apenas lendo texto. O baseline de T31 (10 testes) foi preservado integralmente; nenhuma asserção anterior foi enfraquecida. `endpoints.all.enabled=false` e `details-visible: NEVER` replicam exatamente a política já aprovada em T25 (SBUS) para o mesmo requisito SEC-05, evitando reinventar uma política divergente entre fronteiras irmãs. Escopo de T33–T37 (idempotência com fingerprint, recuperação de publish, waiter/MDC, consumo failure-safe, admissão e pacote produtivo) permanece explicitamente fora desta tarefa. Não há SPEC_DEVIATION em T32.

#### T33: Implementar reserva idempotente com fingerprint

**Status:** Complete

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

**Gate evidence:** O `RedisStatusStore.reserveIdempotency(key, requestId)` herdado de `7de39fb` associava a chave apenas ao `requestId`, sem nenhum fingerprint do payload — uma chave reutilizada com payload divergente simplesmente reproduzia (silenciosamente) o resultado original, violando PAY-02. `RedisStatusStore.reserve(key, requestId, fingerprint)` (novo) grava `{requestId, fingerprint}` como um único valor JSON via `SET NX` — chave e fingerprint ficam atomicamente associados numa única ida ao Redis, sem janela em que a chave exista sem o fingerprint. `IdempotencyFingerprint` (novo, `payment-api/src/main/java/com/example/payments/api/idempotency/`) calcula SHA-256 sobre os campos de negócio delimitados por `|`, normalizando a escala do `BigDecimal` (`stripTrailingZeros`) para que `125.50` e `125.5` sejam o mesmo fingerprint, preservando a equivalência numérica já estabelecida em T8. `ApiPaymentService.submit` agora trata `IdempotencyOutcome.Replay` (mesma chave+fingerprint) devolvendo a identidade original, e `IdempotencyOutcome.Conflict` (mesma chave, fingerprint diferente) lançando `IdempotencyConflictException` **antes** de qualquer serialização/publicação Kafka — `producer.send` nunca é chamado no caminho de conflito, provado por `verify(producer, never())` em unit test. Um novo `IdempotencyConflictExceptionHandler` mapeia o conflito para `409 problem+json`. TTL coerente é agora um invariante de startup: `ApiProperties.validate()` (`@PostConstruct`) rejeita `wait-timeout`/`status-ttl`/`idempotency-ttl` não positivos e exige `idempotency-ttl >= status-ttl` — caso contrário a reserva poderia expirar enquanto o status original ainda está visível, permitindo que uma resubmissão escape à deduplicação. O full gate (`./gradlew test -PwithIT --no-daemon`, `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exportados) passou **55/55**: os 34 de T31+T32 mais 21 novos (`IdempotencyFingerprintUnitTest` 6, `RedisStatusStoreIdempotencyIT` 6 contra Redis real incluindo expiração de TTL, `ApiPropertiesUnitTest` 6, `IdempotencyIT` 2 fim a fim via HTTP real, `ApiPaymentServiceUnitTest` +1) sem redução de nenhum teste anterior.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| PAY-01: chave+requestId+fingerprint associados atomicamente | `RedisStatusStoreIdempotencyIT.java:60-64` — `assertInstanceOf(Reserved.class, ...)` sobre `SET NX` real | primeira reserva grava identidade completa numa única operação | ✅ |
| PAY-02: mesma chave/payload repete identidade | `RedisStatusStoreIdempotencyIT.java:68-76`; `IdempotencyIT.java:66-73` — mesmo `requestId` no segundo `reserve`/segunda submissão HTTP | replay determinístico, não uma nova identidade | ✅ |
| PAY-02: payload divergente retorna conflito e zero publish | `RedisStatusStoreIdempotencyIT.java:80-88` — `assertInstanceOf(Conflict.class, ...)` preserva o `requestId` original; `ApiPaymentServiceUnitTest.java:117-126` — `assertThrows(IdempotencyConflictException.class, ...)` e `verify(producer, never())...`; `IdempotencyIT.java:76-96` — HTTP `409` real e status do `requestId` original intacto após a tentativa rejeitada | conflito determinístico sem publicação nem sobrescrita do dono original | ✅ |
| Conflitos repetidos não sobrescrevem o dono | `RedisStatusStoreIdempotencyIT.java:92-101` — três tentativas divergentes, `requestId` original preservado em todas | `SET NX` nunca perde para uma tentativa posterior | ✅ |
| TTL coerente | `ApiPropertiesUnitTest.java:32-37` — `assertThrows(ConfigurationException.class, ...)` para `idempotency-ttl < status-ttl`; `RedisStatusStoreIdempotencyIT.java:105-113` — reserva expira e libera a chave após o TTL configurado | reserva nunca expira antes do status permanecer visível; expiração real observada | ✅ |
| Fingerprint determinístico e sem colisão de fronteira | `IdempotencyFingerprintUnitTest.java` — mesmo payload/escala numérica equivalente geram o mesmo fingerprint; merchantId, installments, valor e concatenação de campos adjacentes (`"AB"+"1"` vs `"A"+"B1"`) geram fingerprints diferentes | fingerprint reflete identidade de negócio, não formatação textual | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `RedisStatusStoreIdempotencyIT.java:60-113` | PAY-01/02, Done-when T33 (Redis real) | ✅ |
| `IdempotencyIT.java:66-96` | PAY-02, fim a fim via HTTP real | ✅ |
| `ApiPaymentServiceUnitTest.java:117-126` | PAY-02, zero publish no conflito | ✅ |
| `ApiPropertiesUnitTest.java:20-56` | PAY-11, TTL coerente | ✅ |
| `IdempotencyFingerprintUnitTest.java` | PAY-01/02, identidade de negócio do fingerprint | ✅ |

**Adequacy review:** cobertura suficiente e necessária. As asserções verificam o `requestId` observável (não apenas ausência de exceção), o status HTTP e corpo `problem+json` reais, a expiração real do TTL contra Redis via Testcontainers, e a ausência de chamada ao producer no caminho de conflito. O gap de T31/T32 que o `7de39fb` deixou (nenhum fingerprint, replay cego) foi corrigido sem reduzir nenhuma asserção anterior — `replaysOnDuplicateIdempotencyKey` continua verde, apenas com o mock adaptado à nova assinatura `reserve(key, requestId, fingerprint)`. `PAY-11` é parcialmente coberto aqui (retenção coerente com idempotência); a parte de "consulta protegida por índice e autorização" já está coberta por T32 (`X-API-Key`/JWT no endpoint de negócio) e pela query por `requestId` existente — nenhuma nova superfície de consulta foi introduzida nesta tarefa. Não há SPEC_DEVIATION em T33.

#### T34: Recuperar atomicamente falha de publicação inicial

**Status:** Complete

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

**Gate evidence:** O caminho de publicação herdado de `7de39fb` violava PAY-03 de duas formas independentes, ambas confirmadas lendo o código antes de qualquer alteração. (1) Quando `producer.send` falhava, `ApiPaymentService` desfazia apenas o waiter local e lançava `PublishFailedException`: a reserva `idem:<key>` continuava apontando para um `requestId` cujo status ficara em `PENDING`, e qualquer retry com a mesma chave caía no ramo `Replay`, recebendo `202` "ainda processando" até o TTL de 15 minutos expirar — exatamente a "reserva órfã que simula processamento" que a AC proíbe. (2) O replay usava `store.get(requestId).orElse(new StatusEntry(requestId, PROCESSING, null))`, **fabricando** `PROCESSING` (documentado no contrato como "SBUS acknowledged / Core is working") para uma identidade sobre a qual a API não tinha nenhuma informação — uma afirmação sobre o downstream que a API não pode fazer.

O estado de publicação passou a viver na própria reserva, não no status: `IdempotencyReservation` ganhou `publishState` (`PENDING_PUBLISH`/`PUBLISHED`/`PUBLISH_FAILED`) e `publishLeaseExpiresAt`. Essa é a única sede correta porque a reserva tem TTL garantidamente maior ou igual ao do status (invariante de startup criada em T33), então um retry sempre distingue "nunca publicado" de "publicado, status já expirado". Nenhum valor foi acrescentado a `SimulationStatus`: o enum pertence ao artefato publicado `payment-contracts` (AD-002, coordenadas Maven), e T31 já estabeleceu o precedente de não tocar o artefato de outra fronteira. `RedisStatusStore.markPublishState` grava com `SET XX KEEPTTL` — `XX` impede ressuscitar uma reserva já expirada e `KEEPTTL` impede que a marcação estenda a janela de deduplicação. `ApiPaymentService.publishAndAwait` marca `PUBLISHED` imediatamente após o ack (`acks=ALL`) e `PUBLISH_FAILED` no `catch`, e o ramo `ResumePublish` republica sob o **mesmo** `requestId`. O lease (`payment.simulation.publish-lease`, default 30s, espelhando `OutboxProperties.lease` do SBUS/T27) resolve o conflito entre as duas metades da AC: sem ele, toda submissão concorrente duplicada republicaria a mesma identidade; com ele, uma tentativa genuinamente em voo é `Replay` e só uma tentativa morta (lease vencido) ou uma falha reportada é `ResumePublish`. A janela entre o ack e a marcação republica a mesma identidade num retry, o que PAY-06 exige explicitamente que o downstream absorva; está anotado no código como trade-off documentado, não como lacuna.

Full gate (`./gradlew test -PwithIT --no-daemon`, com `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exportados) passou **73/73 testes, 0 falhas, 0 skipped**, contra Kafka/Redis/Apicurio reais via Testcontainers: os 55 de T31–T33 mais 18 novos (`PublishFailureIT` 5 fim a fim com broker derrubado, `PublishStateReservationIT` 7 contra Redis real, `ApiPaymentServiceUnitTest` +5, `ApiPropertiesUnitTest` +1). Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| PAY-03: falha de publicação deixa estado recuperável, não trabalho aceito | `PublishFailureIT.java:97-100` — `assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatus())`, content-type `problem+json` e corpo `"status":503` | `503` honesto contra broker real derrubado, nunca `202` | ✅ |
| PAY-03: reserva marcada como não publicada | `PublishFailureIT.java:110-111` — `assertEquals(PublishState.PUBLISH_FAILED, reservation.publishState())` sobre o valor realmente lido do Redis | estado de publicação persistido e observável | ✅ |
| PAY-03: nenhuma reserva órfã simulando processamento | `PublishFailureIT.java:122` — `assertEquals("PENDING", entry.status().name())`; `ApiPaymentServiceUnitTest.java:152` — `assertTrue(...noneMatch(status == SENT_TO_SBUS \|\| status == PROCESSING))` | status jamais afirma progresso downstream após falhar o publish | ✅ |
| PAY-03: replay sem status conhecido não fabrica `PROCESSING` | `ApiPaymentServiceUnitTest.java:182` — `assertEquals(SimulationStatus.TIMEOUT, result.entry().status())` | ausência de informação vira `TIMEOUT`, não uma afirmação sobre o Core | ✅ |
| Done-when: janela de retry preserva o mesmo `requestId` | `PublishFailureIT.java:135` — `assertEquals(firstRequestId, secondRequestId)` após duas submissões HTTP reais falhadas; `ApiPaymentServiceUnitTest.java:166-169` — `assertEquals("original-request-id", result.entry().requestId())` e `verify(producer).send(eq("original-request-id"), ...)` | retry recupera a identidade original, não cria uma nova | ✅ |
| Done-when: janela de crash (owner morreu sem publicar) | `PublishStateReservationIT.java:98-99` — `assertInstanceOf(ResumePublish.class, ...)` e `assertEquals(owner, ...requestId())` após o lease vencer, contra Redis real | identidade órfã é recuperada, não esperada até o TTL | ✅ |
| Done-when: janela de send failure é retomável de imediato | `PublishStateReservationIT.java:111-112` — `ResumePublish` com o `requestId` original logo após `PUBLISH_FAILED`, sem aguardar o lease | falha reportada não custa uma espera de lease ao cliente | ✅ |
| Done-when: janela de timeout preserva o mesmo `requestId` | `ApiPaymentServiceUnitTest.java:194-197` — `assertEquals(published.getValue(), result.entry().requestId())`, `assertEquals(SENT_TO_SBUS, ...status())`, `assertTrue(result.timedOut())` | timeout mantém a identidade publicada e o status factual | ✅ |
| Ack confirmado marca `PUBLISHED` e só então `SENT_TO_SBUS` | `ApiPaymentServiceUnitTest.java:132,136` — `assertEquals(PublishState.PUBLISHED, state.getValue())` e `assertEquals(SENT_TO_SBUS, saved.getAllValues().getLast().status())` | marcação segue o ack do broker, não a intenção de enviar | ✅ |
| Publicação confirmada nunca vira republicação | `PublishStateReservationIT.java:125-126` — `Replay` mesmo após o lease vencer | `PUBLISHED` é terminal para fins de retry | ✅ |
| Tentativa em voo não é republicada por duplicata concorrente | `PublishStateReservationIT.java:84-85` — `Replay` com lease vigente | lease evita publicação dupla da mesma identidade | ✅ |
| PAY-02 preservado sobre os novos ramos | `PublishStateReservationIT.java:138-139` — `Conflict` com o `requestId` original mesmo em `PUBLISH_FAILED` | payload divergente continua conflito determinístico | ✅ |
| PAY-11: recuperação não estende a janela de deduplicação | `PublishStateReservationIT.java:152-153` — `assertTrue(remaining > 0)` e `assertTrue(remaining <= IDEMPOTENCY_TTL_MILLIS - 1_400)` via `PTTL` real | `KEEPTTL` preserva o vencimento original | ✅ |
| Marcação não ressuscita reserva expirada | `PublishStateReservationIT.java:163` — `assertEquals(0L, inspector.sync().exists("idem:" + key))` | `XX` não recria identidade sem reserva viva | ✅ |
| Recuperação é por chave, não global | `PublishFailureIT.java:146` — `assertNotEquals(reservation(firstKey).requestId(), reservation(secondKey).requestId())` | chaves distintas mantêm identidades distintas mesmo no caminho de falha | ✅ |
| Lease inválido falha no startup | `ApiPropertiesUnitTest.java:63` — `assertThrows(ConfigurationException.class, properties::validate)` | lease não positivo tornaria toda reserva imediatamente retomável | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `PublishFailureIT.java:97-100` | PAY-03, Done-when send failure (contrato HTTP) | ✅ |
| `PublishFailureIT.java:110-111,122` | PAY-03, Done-when "não simulam processamento" | ✅ |
| `PublishFailureIT.java:135,146` | Done-when retry preserva `requestId`; PAY-01 por chave | ✅ |
| `PublishStateReservationIT.java:84-85,98-99,111-112,125-126` | Done-when crash/send-failure/retry windows | ✅ |
| `PublishStateReservationIT.java:138-139` | PAY-02 sob os novos estados | ✅ |
| `PublishStateReservationIT.java:152-153,163` | PAY-11 TTL coerente; recuperação sem ressurreição | ✅ |
| `ApiPaymentServiceUnitTest.java:131-136,147-152` | PAY-03 coordenação reservation/ack nos dois desfechos | ✅ |
| `ApiPaymentServiceUnitTest.java:166-169,181-183,194-197` | Done-when resume/replay/timeout | ✅ |
| `ApiPropertiesUnitTest.java:63` | branch de validação do lease | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção de payload verifica valor ou estado observável — o `publishState` lido do Redis real, o `requestId` devolvido, o status HTTP e o `PTTL` efetivo — e não apenas que um método foi chamado; onde há `verify`, ele acompanha (nunca substitui) uma asserção sobre o resultado. Os dois bugs pré-existentes têm teste que falharia na implementação anterior: `retryingAfterAFailedPublishKeepsTheSameRequestId` porque antes o retry recebia `202` de replay, e `replayWithoutAStoredStatusNeverReportsDownstreamProcessing` porque antes o valor asserido era literalmente `PROCESSING`. `aDifferentKeyAfterAFailedPublishGetsItsOwnIdentity` é o controle negativo do novo ramo `ResumePublish` (uma implementação que resolvesse identidade fora da chave passaria em todo o resto). MDC e cleanup de waiter em todos os caminhos permanecem explicitamente com T35, que os possui por `Where` e por requisito (PAY-10); esta tarefa não os alterou. Não há SPEC_DEVIATION em T34.

#### T35: Limitar waiter, MDC e fallback de status

**Status:** Complete

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

**Gate evidence:** Auditando o caminho de coordenação herdado de `7de39fb` contra PAY-10 e PAY-09, três lacunas reais foram encontradas. (1) **MDC vazava em toda saída que não fosse o caminho feliz**: `MDC.clear()` estava numa linha solta após `coordinator.await(...)`, então uma `PublishFailedException` (ou qualquer falha de `store.save`/`markPublishState`) devolvia a thread ao pool ainda carregando `requestId`/`correlationId`/`traceId` do request anterior — logs da requisição seguinte herdariam a identidade errada. O bloco passou a ser `try { ... } finally { MDC.clear(); }`, cobrindo resultado, timeout, interrupção, shutdown e falha de publicação. (2) **Shutdown não removia o registro local**: `close()` completava os futures excepcionalmente mas nunca esvaziava o mapa `waiters`; PAY-10 exige remover MDC **e registro local** em todos os caminhos, e shutdown é um deles — foi adicionado `waiters.clear()`. (3) **`register()` durante o shutdown criava um waiter órfão**: uma requisição que chegasse depois do `@PreDestroy` entrava no mapa recém-esvaziado e estacionava pelo budget inteiro contra uma API que está morrendo; `register` passou a devolver um future já liberado, sem registrar nada.

Para PAY-09, o fallback de status durável não tinha nenhum limite: `@Client("${sbus.base-url}")` usava o timeout default do cliente HTTP e `fromSbus` engolia a exceção, de modo que um SBUS lento esticava toda consulta de status e cada requisição pagava esse custo indefinidamente. O client passou a ser declarado por service id (`@Client(id = "sbus")`), com `connect-timeout`/`read-timeout` explícitos em `micronaut.http.services.sbus`, e cada chamada carrega o header `X-Service-Name` com a identidade do chamador. `SbusStatusGateway` (novo, no pacote `coordination` desta tarefa) acrescenta a política de falha: o timeout HTTP limita **uma** chamada, o circuito limita o custo **repetido**, com `failure-threshold`/`open-duration` tipados e validados no startup por `SbusFallbackProperties`. O fallback continua best-effort por design: um SBUS indisponível degrada para "nenhuma informação adicional", nunca para um erro ao cliente.

Full gate (`./gradlew test -PwithIT --no-daemon`, com `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exportados) passou **94/94 testes, 0 falhas, 0 skipped**: os 73 de T31–T34 mais 21 novos (`ResponseCoordinatorUnitTest` 8, `SbusStatusGatewayUnitTest` 7, `SbusFallbackBudgetIT` 2 contra um SBUS stub genuinamente lento, `ApiPaymentServiceUnitTest` +4 de MDC). Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| PAY-10: waiter termina por resultado e o registro é removido | `ResponseCoordinatorUnitTest.java:54-55` — `assertEquals(Optional.of(terminal), result)` e `assertEquals(0, coordinator.pendingCount())` | resultado devolvido e registro local zerado | ✅ |
| PAY-10: waiter termina por timeout dentro do budget | `ResponseCoordinatorUnitTest.java:67-70` — `Optional.empty()`, `pendingCount()==0`, `elapsed >= WAIT_TIMEOUT` e `elapsed < WAIT_TIMEOUT×10` | espera termina no orçamento, nem antes nem indefinidamente | ✅ |
| PAY-10: waiter termina por interrupção, com flag preservada | `ResponseCoordinatorUnitTest.java:93-96` — retorno em ≤2s, `Optional.empty()`, `assertTrue(interruptFlagRestored.get())`, `pendingCount()==0` | interrupção encerra a espera sem engolir o sinal | ✅ |
| PAY-10: waiter termina por shutdown e o registro é limpo | `ResponseCoordinatorUnitTest.java:107` — `assertEquals(0, coordinator.pendingCount())` após `close()` com três waiters | shutdown é caminho de terminação como os demais | ✅ |
| PAY-10: shutdown não queima o budget restante | `ResponseCoordinatorUnitTest.java:131-133` — espera de 30s liberada em ≤5s, `Optional.empty()`, `pendingCount()==0` | shutdown libera conexões em vez de segurá-las | ✅ |
| PAY-10: registro tardio não fica órfão | `ResponseCoordinatorUnitTest.java:142-144` — `assertTrue(future.isDone())`, `await` vazio, `pendingCount()==0` | request pós-shutdown não estaciona nem registra | ✅ |
| PAY-10: status não terminal não é resultado | `ResponseCoordinatorUnitTest.java:156-157` — `pendingCount()==1` e `assertFalse(...isDone())` com `PENDING` no store | só estado terminal libera o waiter | ✅ |
| PAY-10: registro duplicado não multiplica waiter | `ResponseCoordinatorUnitTest.java:165-166` — `assertSame(first, second)` e `pendingCount()==1` | registro local é idempotente | ✅ |
| PAY-10: MDC removido no caminho de resultado | `ApiPaymentServiceUnitTest.java:209-211` — `assertNull(MDC.get("requestId"/"correlationId"/"traceId"))` | thread devolvida limpa | ✅ |
| PAY-10: MDC removido no timeout | `ApiPaymentServiceUnitTest.java:221-223` — as mesmas três assertions após submit com timeout | idem | ✅ |
| PAY-10: MDC removido quando o publish falha | `ApiPaymentServiceUnitTest.java:233-235` — as mesmas três assertions após `PublishFailedException` | caminho de exceção não vaza identidade | ✅ |
| PAY-10: MDC removido quando o shutdown libera o waiter | `ApiPaymentServiceUnitTest.java:245-247` — as mesmas três assertions após `IllegalStateException("API shutting down")` | quarto caminho de terminação coberto | ✅ |
| PAY-09: fallback identifica o serviço chamador | `SbusStatusGatewayUnitTest.java:52-53` — `assertEquals(Optional.of(response), result)` e `verify(client).getStatus("req-1", SERVICE_NAME)` | identidade de serviço viaja em toda chamada | ✅ |
| PAY-09: SBUS indisponível degrada, não falha | `SbusStatusGatewayUnitTest.java:60` — `assertEquals(Optional.empty(), gateway.getStatus("req-1"))` | fallback é best-effort, nunca erro ao cliente | ✅ |
| PAY-09: circuito abre e para de gastar o timeout | `SbusStatusGatewayUnitTest.java:70-73` — `assertTrue(gateway.circuitOpen())` e `verify(client, never()).getStatus(eq("req-after-open"), ...)` | custo repetido limitado, não só o custo unitário | ✅ |
| PAY-09: circuito fecha após a janela | `SbusStatusGatewayUnitTest.java:85-87` — `assertFalse(circuitOpen())` e `verify(client).getStatus(eq("req-recheck"), ...)` | indisponibilidade não é permanente | ✅ |
| PAY-09: falhas isoladas não abrem o circuito | `SbusStatusGatewayUnitTest.java:102-103` — `assertFalse(circuitOpen())` e `verify(client, times(2))` após sucesso intercalado | só falha consecutiva conta | ✅ |
| PAY-09: política de falha inválida falha no startup | `SbusStatusGatewayUnitTest.java:111,119` — `assertThrows(ConfigurationException.class, invalid::validate)` para `open-duration` zero e threshold < 1 | circuito sem limite não passa da configuração | ✅ |
| Done-when: fallback não excede budget (fim a fim) | `SbusFallbackBudgetIT.java:113-115` — `assertEquals(HttpStatus.NOT_FOUND, ...)` e `assertTrue(elapsed < SBUS_DELAY)` contra um SBUS stub que dorme 5s | consulta não herda a lentidão real do SBUS | ✅ |
| Done-when: budget repetido também limitado (fim a fim) | `SbusFallbackBudgetIT.java:129-132` — `assertEquals(callsBeforeOpen, SBUS_CALLS.get())` e `assertTrue(elapsed < READ_BUDGET)` | circuito aberto não chama o SBUS nem paga o read budget | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `ResponseCoordinatorUnitTest.java:54-55,67-70,93-96` | PAY-10 result/timeout/interruption | ✅ |
| `ResponseCoordinatorUnitTest.java:107,131-133,142-144` | PAY-10 shutdown e registro tardio | ✅ |
| `ResponseCoordinatorUnitTest.java:156-157,165-166` | PAY-10 integridade do registro local | ✅ |
| `ApiPaymentServiceUnitTest.java:209-247` | PAY-10 MDC nos quatro caminhos | ✅ |
| `SbusStatusGatewayUnitTest.java:52-53,60` | PAY-09 identidade e degradação | ✅ |
| `SbusStatusGatewayUnitTest.java:70-73,85-87,102-103` | PAY-09 circuito (abre/fecha/discrimina) | ✅ |
| `SbusStatusGatewayUnitTest.java:111,119` | PAY-09 validação da política | ✅ |
| `SbusFallbackBudgetIT.java:113-115,129-132` | Done-when "fallback não excede budget" | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Os quatro caminhos de terminação nomeados por PAY-10 (resultado, timeout, interrupção, shutdown) têm cada um um teste com **duas** asserções distintas — o desfecho da espera e `pendingCount()==0` — e o MDC é verificado por chave nomeada, não por "não lançou exceção". Os três bugs pré-existentes têm teste que falharia antes: `leavesNoMdcBehindWhenThePublishFails` (o `MDC.clear()` era inalcançável na exceção), `shutdownReleasesEveryWaiterAndClearsTheLocalRegistry` (o mapa nunca era esvaziado) e `registeringAfterShutdownLeavesNoWaiterBehind` (o waiter tardio entrava no mapa). O budget do fallback é provado nos dois níveis que importam: o unitário mostra o mecanismo (zero chamadas com o circuito aberto) e o IT mostra o efeito observável contra um servidor HTTP que realmente dorme 5 segundos, com asserção sobre tempo decorrido e sobre a contagem real de chamadas recebidas pelo stub. `anInterveningSuccessKeepsTheCircuitClosed` é o controle negativo: sem ele, um circuito que contasse falhas totais em vez de consecutivas passaria em todo o resto. Limites de admissão por recurso/tenant e falha fechada sob Redis indisponível continuam explicitamente com T37 (CAP-03), que os possui por `Where` e por requisito; esta tarefa não tocou `ratelimit`/`filter`. Não há SPEC_DEVIATION em T35.

#### T36: Tornar consumo de respostas failure-safe

**Status:** Complete

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

**Gate evidence:** O `PaymentResponseConsumer` herdado de `7de39fb` confirmava silenciosamente tudo o que não conseguia processar. Uma falha de decode caía em `LOG.error(...); return;` — sem `offsetStrategy` declarado, o auto-commit do cliente Kafka avançava o offset e o evento final desaparecia para sempre, sem DLQ, sem alerta e sem nenhum vestígio dos bytes originais. Um tipo de evento inesperado no tópico terminal tinha o mesmo destino. Uma falha de Redis (`store.save`) escapava do método sem retry nem roteamento, deixando o desfecho do pagamento à mercê do comportamento default do framework. E um evento terminal repetido **sobrescrevia** o resultado já escolhido: um `FAILED` atrasado ou reentregue depois de um `COMPLETED` gravado trocava o desfecho do pagamento, violando PAY-06 justamente no cenário que PAY-06 nomeia (republicação após crash entre o ack do Kafka e a marcação da outbox, garantida por T28).

O consumer passou a `offsetStrategy = SYNC_PER_RECORD` com `errorStrategy = RETRY_ON_ERROR` (mesma configuração já auditada em `PaymentRequestedConsumer` do SBUS/T27 para o mesmo problema), de modo que o offset só avança após retorno normal. As falhas passaram a ser **classificadas**, não agrupadas: conteúdo que nunca será legível vai para `Topics.DLQ` com os bytes originais e headers `x-dlq-origin-topic`/`x-dlq-stage`/`x-dlq-reason` (`ResponseDeadLetters`, novo); falha de aplicação (Redis indisponível) é retentada dentro de um orçamento tipado (`ResponseConsumerProperties`, `max-attempts`/`retry-delay`, validados no startup) e só então vai para a DLQ com stage `apply`; e `AvroCodecUnavailableException` — capacidade, não conteúdo — é **relançada** para reentrega, porque tratá-la como poison queimaria um resultado de pagamento perfeitamente válido só porque o pool estava momentaneamente cheio. A publicação na DLQ é síncrona com `acks=ALL` e sua falha não é engolida: propaga, o offset não commita, nada é confirmado sem estar em lugar recuperável. Para PAY-06, `apply` lê o estado atual antes de gravar e, se já for terminal, preserva-o: ainda acorda o waiter e publica no canal, mas nunca reescreve o desfecho. O codec continua o `AvroSerde` limitado de T9, agora com a capacidade configurada verificada como invariante observável.

Full gate (`./gradlew test -PwithIT --no-daemon`, com `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exportados) passou **104/104 testes, 0 falhas, 0 skipped**: os 94 de T31–T35 mais 10 novos (`ResponseConsumerFailureIT` 6 e `ResponseConsumerRedisOutageIT` 1 — sete ITs, conforme o Done-when — e `PaymentResponseConsumerUnitTest` 3). Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| PAY-09: poison vai para DLQ, não é confirmado silenciosamente | `ResponseConsumerFailureIT.java:123-126` — `assertArrayEquals(garbage, dead.value())`, `x-dlq-origin-topic` = tópico de origem, `x-dlq-stage` = `decode`, reason não nulo | mensagem ilegível reaparece na DLQ com bytes originais e motivo | ✅ |
| PAY-09: tipo de evento inesperado é DLQ, não status inventado | `ResponseConsumerFailureIT.java:143-144` — `assertEquals("decode", header(...))` e `assertTrue(store.get(requestId).isEmpty())` | evento fora do contrato não vira estado de negócio | ✅ |
| PAY-09: poison não bloqueia o resultado válido seguinte | `ResponseConsumerFailureIT.java:158-160` — entrada presente, `COMPLETED` e `authorizationCode` `654321` | DLQ desobstrui a partição em vez de travá-la | ✅ |
| PAY-09: Redis indisponível é retry/DLQ observável | `ResponseConsumerRedisOutageIT.java:113-115` — `assertEquals("apply", ...stage)`, tópico de origem e `assertArrayEquals(payload, dead.value())` com o container Redis realmente parado | resultado sobrevive à queda do Redis como trabalho recuperável | ✅ |
| PAY-09: falta de capacidade do codec é reentrega, não DLQ | `PaymentResponseConsumerUnitTest.java:68-71` — `assertThrows(AvroCodecUnavailableException.class, ...)`, mensagem exata e `verify(deadLetters, never()).route(...)` | transiente não é confundido com poison | ✅ |
| PAY-09: DLQ que não confirma nunca vira ack | `PaymentResponseConsumerUnitTest.java:80` — `assertThrows(IllegalStateException.class, () -> consumer.receive(record()))` quando o envio à DLQ falha | offset não avança sem destino confirmado | ✅ |
| PAY-09: poison é classificado no stage correto | `PaymentResponseConsumerUnitTest.java:59` — `verify(deadLetters).route(any(), eq(STAGE_DECODE), any())` | roteamento nomeia onde a falha ocorreu | ✅ |
| PAY-06: duplicata terminal idêntica é idempotente | `ResponseConsumerFailureIT.java:173-174` — `COMPLETED` e `authorizationCode` `111111` inalterados após reentrega | repetição não altera o resultado | ✅ |
| PAY-06: repetição contraditória não reescreve o desfecho | `ResponseConsumerFailureIT.java:186-187` — segue `COMPLETED` com `222222` após um `FAILED` posterior para o mesmo `requestId` | o desfecho já escolhido é final | ✅ |
| Done-when: serializer é bounded | `ResponseConsumerFailureIT.java:196-197` — `assertEquals(CODEC_POOL_SIZE, snapshot.capacity())` e `capacity == available + borrowed` | capacidade do codec é a configurada, não ilimitada | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `ResponseConsumerFailureIT.java:123-126,143-144` | PAY-09 poison e tipo inesperado → DLQ | ✅ |
| `ResponseConsumerFailureIT.java:158-160` | PAY-09 partição não trava | ✅ |
| `ResponseConsumerFailureIT.java:173-174,186-187` | PAY-06 duplicata idêntica e contraditória | ✅ |
| `ResponseConsumerFailureIT.java:196-197` | Done-when codec limitado | ✅ |
| `ResponseConsumerRedisOutageIT.java:113-115` | PAY-09 Redis outage → retry/DLQ | ✅ |
| `PaymentResponseConsumerUnitTest.java:59,68-71,80` | PAY-09 classificação de falhas e ack honesto | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção de DLQ verifica os **bytes originais** e os headers de proveniência, não apenas que "algo chegou": um roteamento que enviasse um placeholder ou perdesse o payload falharia. As duas duplicatas de PAY-06 são testadas separadamente porque testam coisas diferentes — a idêntica prova idempotência, e a contraditória (`COMPLETED` seguido de `FAILED`) prova que o desfecho é imutável, que é o que a AC realmente exige; um `save` incondicional passaria na primeira e falharia na segunda. `aCodecCapacityShortageIsRedeliveredInsteadOfDeadLettered` é o controle negativo da classificação: sem ele, um `catch (Exception) → DLQ` genérico passaria em todos os outros testes de poison enquanto destruía resultados válidos sob saturação. O IT de Redis vive em classe própria porque para o container de verdade; o `awaitConsumerAssignment` existe porque o listener usa `offsetReset = LATEST` e um registro produzido antes da atribuição simplesmente nunca seria visto — sem essa prova de vivacidade, um teste verde não significaria nada. Admissão, limites por recurso/tenant e pacote produtivo seguem com T37. Não há SPEC_DEVIATION em T36.

#### T37: Completar admissão e pacote produtivo da API

**Status:** Complete

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

**Gate evidence:** O `RedisRateLimiter` herdado de `7de39fb` fazia exatamente o que o Done-when proíbe: quando o `eval` no Redis falhava, `localTryAcquire` concedia à instância **o orçamento global inteiro** (`limitForPeriod`). Com quatro réplicas e Redis fora, a frota admitiria 4× a rajada aprovada — justamente durante a falha da coordenação que existia para limitá-la. O fallback passou a ser `max(1, limitForPeriod / instances)`: cada instância fica com a sua fração, e a soma da frota nunca ultrapassa o orçamento aprovado. `instances` é configuração tipada e validada (`>= 1`), exposta como `PAYMENT_API_INSTANCES` no Compose e no `.env.example`, porque subestimá-la afrouxa a admissão degradada. Também havia um único orçamento global: agora há dois, por recurso (`METHOD:path`) e por tenant, ambos verificados antes de admitir, com contadores locais por escopo. O tenant é identificado por SHA-256 truncado da credencial, nunca pela credencial em texto, de modo que nenhuma chave de Redis, log ou métrica a carrega.

O pacote produtivo espelha o de T30 (`payment-sbus`), adaptado à superfície real desta fronteira (admissão HTTP e Redis, não Postgres/outbox): `Dockerfile` multi-stage com bases fixadas por tag **e** digest, runtime `10001:10001`, healthcheck sem instalar pacote e build a partir dos repositórios Maven publicados de contracts **e** feature-control; `compose.yaml` app-only na rede externa do sandbox (AD-003) com `read_only`, `cap_drop: ALL`, `no-new-privileges`; `.env.example` sem valor atribuído a segredo; CI local com unit, IT, imagem, SBOM SPDX e Trivy bloqueando HIGH/CRITICAL; `deploy/` e `scripts/` com verificadores determinísticos; `README.md`, `AGENTS.md`, nove documentos em `docs/`, ADR-0001 aceito e três runbooks owned com alertas correspondentes.

Gates executados de fato: `./gradlew test -PwithIT --no-daemon` (com `JWT_SIGNATURE_SECRET`/`PAYMENT_API_KEY` exportados) passou **115/115 testes, 0 falhas, 0 skipped** — os 104 de T31–T36 mais 11 novos (`AdmissionControlIT` 5, `AdmissionRedisOutageIT` 1, totalizando os seis ITs pedidos, e `RedisRateLimiterUnitTest` 5). `./gradlew build --no-daemon` passou. `scripts/verify-docs.sh` passou três testes e validou 17 documentos. `deploy/verify.sh --structural` passou dez testes e `docker compose config -q`. A imagem `payment-api:t37` foi construída de verdade pelo Dockerfile standalone com os dois repositórios Maven como build contexts; a inspeção confirmou `User=10001:10001`, entrypoint `/app/bin/payment-api`, healthcheck de liveness e label `payment-api`. O smoke de runtime completo (`deploy/verify.sh` sem flag) e a execução remota da CI ficam `NOT_RUN`, registrados como tal em `docs/operations.md` e `docs/testing.md`, nunca presumidos verdes.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| CAP-03: Redis down não multiplica o limite por instância | `AdmissionRedisOutageIT.java:88-93` — com `instances=4` e orçamento 8, `assertFalse(admitted.contains(TOO_MANY_REQUESTS))` para a fração e `assertTrue(rejected.stream().allMatch(... == TOO_MANY_REQUESTS))` para o excedente, com o container Redis realmente parado | instância admite a sua fração, não o orçamento da frota | ✅ |
| CAP-03: fração é derivada do tamanho da frota | `RedisRateLimiterUnitTest.java:34-38` — `assertEquals(2, limiter.degradedLimitForPeriod())` e terceira aquisição negada com limite 8 e 4 instâncias | divisor aplicado, não ignorado | ✅ |
| CAP-03: fração nunca chega a zero | `RedisRateLimiterUnitTest.java:44-47` — `assertEquals(1, degradedLimitForPeriod())` com limite 2 e 8 instâncias | degradação não fecha a rota por arredondamento | ✅ |
| CAP-03: frota de uma instância não é penalizada | `RedisRateLimiterUnitTest.java:53-58` — três aquisições aceitas e a quarta negada com limite 3 | o divisor só age quando há frota | ✅ |
| CAP-03: `202` dentro do orçamento | `AdmissionControlIT.java:97` — `assertEquals(HttpStatus.ACCEPTED, accepted.getStatus())` sobre POST real | requisição aprovada é aceita | ✅ |
| CAP-03: `429` com `Retry-After` além do orçamento | `AdmissionControlIT.java:110-111` — `assertEquals(TOO_MANY_REQUESTS, rejected.getStatus())` e `assertEquals("1", ...get("Retry-After"))` | rejeição explícita e acionável, sem enfileiramento silencioso | ✅ |
| Limite por tenant isola chamadores | `AdmissionControlIT.java:124` — `assertEquals(ACCEPTED, submit(quiet).getStatus())` depois de o tenant ruidoso ser rejeitado | um chamador não consome a rota inteira | ✅ |
| Limite por recurso limita a rota | `AdmissionControlIT.java:140-142` — `assertEquals(TOO_MANY_REQUESTS, lastRejection)` e `assertTrue(admitted <= RESOURCE_BUDGET)` distribuindo entre cinco tenants | orçamento da rota vale acima da soma dos tenants | ✅ |
| Escopos não colidem entre si | `RedisRateLimiterUnitTest.java:64-67`, `:81-83` — janelas locais independentes por escopo e chaves `rl:api-admission:tenant-a:` / `tenant-b:` distintas | orçamento por escopo é real, não um contador único | ✅ |
| SEC: credencial de tenant não vira chave | `AdmissionControlIT.java:151-160` — duas chaves `rl:*`, uma `rl:api-tenant-admission:`, e `noneMatch(key.contains(tenant))` lidas do Redis real | identidade por hash, credencial nunca persistida | ✅ |
| SEC-07: bases pinadas, runtime non-root e health sem pacote | `deploy/test_release_package.py:16-36` — duas linhas `FROM` com `:` e `@sha256:`, `USER 10001:10001`, `installDist`, ausência de `apk`/`apt-get` | imagem mínima, pinada e sem privilégio | ✅ |
| ORG-03: build só de dependências publicadas | `deploy/test_release_package.py:28-31` — `COPY --from=contracts-repository`, `COPY --from=feature-control-repository` e ausência de `project(':` | nenhuma dependência de source cross-root | ✅ |
| ORG-03: Compose app-only na rede externa | `deploy/test_release_package.py:38-45` — `assertEqual(["api"], ...)`, `external: true` e ausência de seis serviços de infraestrutura | sandbox mantém ownership da infraestrutura | ✅ |
| SEC-07: filesystem e capabilities restritos | `deploy/test_release_package.py:47-51` — read-only, `no-new-privileges`, `cap_drop: ALL`, `user` | runtime sem privilégio e somente leitura | ✅ |
| CAP-03: escalar réplicas escala o divisor | `deploy/test_release_package.py:53-58` — `PAYMENT_API_INSTANCES` no Compose e no `.env.example`, e `instances: ${PAYMENT_API_INSTANCES:1}` no `application.yml` | a réplica sabe o tamanho da frota; sem isso ela se daria o orçamento inteiro | ✅ |
| SEC-08: CI unit/IT/imagem/SBOM/scan/docs | `deploy/test_release_package.py:60-69` — cada marker obrigatório, `exit-code: '1'` e `severity: 'HIGH,CRITICAL'` | pipeline bloqueante cobre supply chain e gates locais | ✅ |
| Nenhum segredo no env versionado | `deploy/test_release_package.py:71-74` — `assertNotRegex(...password\|secret\|token...)` e `PAYMENT_API_KEY=` vazio | `.env.example` não atribui credencial | ✅ |
| Runbooks owned cobrem admissão, DLQ e rollback | `deploy/test_release_package.py:76-80` — três `assertIn(...md)` | índice operacional aponta para os três procedimentos | ✅ |
| DOC-01..04: pacote, links, claims e ADR | `scripts/test_docs.py:18-34` — resultado vazio para a árvore real, pacote ausente e link quebrado detectados | pacote proporcional completo e validador discriminante | ✅ |
| Imagem construída possui identidade observável | inspeção local de `payment-api:t37` — `User=10001:10001`, entrypoint `/app/bin/payment-api`, healthcheck de liveness, label `payment-api` | artefato real corresponde ao contrato estrutural | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `AdmissionRedisOutageIT.java:88-93` | CAP-03, Done-when "Redis down não multiplica limite" | ✅ |
| `RedisRateLimiterUnitTest.java:34-38,44-47,53-58` | CAP-03, branches do orçamento degradado | ✅ |
| `RedisRateLimiterUnitTest.java:64-67,81-83` | CAP-03, isolamento de escopo | ✅ |
| `AdmissionControlIT.java:97,110-111` | Done-when "429/202 são testados" | ✅ |
| `AdmissionControlIT.java:124,140-142` | limites por tenant e por recurso | ✅ |
| `AdmissionControlIT.java:151-160` | SEC, credencial fora da chave de orçamento | ✅ |
| `deploy/test_release_package.py:16-58` | SEC-07/ORG-03/CAP-03, imagem e Compose | ✅ |
| `deploy/test_release_package.py:60-80` | SEC-08 e runbooks owned | ✅ |
| `scripts/test_docs.py:18-34` | DOC-01..04, pacote e validação | ✅ |

**Adequacy review:** cobertura suficiente e necessária. O bug de CAP-03 é provado nos dois níveis: o unitário mostra a aritmética do divisor e o IT mostra o efeito observável com o container Redis parado de verdade — na implementação anterior as oito requisições teriam sido admitidas e o teste falharia. Os testes estruturais afirmam valores e superfícies observáveis, e os negativos de `scripts/test_docs.py` provam que pacote ausente e link quebrado realmente falham, senão o validador não discriminaria nada. `aTenantBudgetKeyIdentifiesTheCallerWithoutStoringItsCredential` lê as chaves do Redis real: uma implementação que usasse a credencial em texto passaria em todos os outros testes de limite. `aSingleInstanceFleetKeepsItsFullBudgetWhenRedisIsDown` é o controle negativo do divisor, impedindo que a correção vire uma degradação permanente. A execução remota da CI e o smoke de runtime dependente do sandbox não foram presumidos; ambos estão registrados como `NOT_RUN` conforme EDG-05, e a imagem foi construída localmente para não deixar o Dockerfile verificado apenas por leitura de texto.

**Desvio de processo (divulgado):** a rastreabilidade de `spec.md` deveria ter sido atualizada no commit de cada tarefa. Ela foi atualizada apenas neste commit, cobrindo PAY-03, PAY-06, PAY-09, PAY-10 (T34–T36) junto com CAP-03, ORG-03, SEC-07, SEC-08 e DOC-01..04 (T37). Os commits de T34–T36 permanecem como estão: reescrever histórico já publicado localmente seria pior do que registrar o desvio. Nenhum gate foi afetado.

### Phase 7 — `async-redis-service`

#### T38: Relocar serviço Redis para build standalone

**Status:** Complete

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

**Gate evidence:** Ao contrário de `payment-api`/`payment-sbus`/`payment-core-mock`, esta fronteira já ocupava o nome definitivo da raiz em AD-001, então a extração foi feita **no lugar**, não por cópia para um diretório novo: `async-redis-service` ganhou `settings.gradle`, `gradle.properties`, wrapper próprio (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) e um `build.gradle` autossuficiente com toolchain, `repositories` e configuração de `Test` próprios — antes ele herdava tudo isso do `subprojects {}` da raiz do monorepo e não tinha wrapper algum. O `include 'async-redis-service'` da raiz antiga foi **mantido** por MIG-02 (“old location permanece até equivalência ser provada”), seguindo o precedente de T19/T24/T31; ambos os builds foram executados e passam, então a equivalência é observada, não presumida.

O baseline foi medido antes de qualquer alteração com o comando do build raiz (`./gradlew :async-redis-service:test -PwithIT --no-daemon`): **6 testes, 0 falhas** (`AsyncRedisFlowIT` 2, `AsyncBackpressureIT` 1, `AsyncDlqIT` 1, `AsyncRateLimiterUnitTest` 2). Depois da extração, o gate da própria fronteira (`async-redis-service/./gradlew test -PwithIT --no-daemon`) passou **10/10 testes, 0 falhas, 0 skipped**: os 6 métodos baseline preservados byte a byte (nenhum removido, pulado ou enfraquecido) mais 4 novos em `StandaloneBoundaryTest`. O build raiz continua verde com os mesmos 10.

Uma tentativa de trocar a provisão de Redis dos ITs para Testcontainers foi **revertida deliberadamente**. Ela não é exigida pelo `Done when` de T38 e não funciona neste ambiente: `UnixSocketClientProviderStrategy` e `DockerDesktopClientProviderStrategy` falham as duas com `BadRequestException (Status 400)` contra o Docker Desktop 29.3.1 (API 1.54) instalado, embora `payment-api` — mesmas versões de `testcontainers-bom:1.20.4`, `com.redis:testcontainers-redis:2.2.2` e `docker-java 3.4.0` — passe no mesmo host (verificado rodando `RedisStatusStoreIdempotencyIT` com `--rerun-tasks`). Como a divergência não foi explicada e o arranjo anterior funciona, os ITs continuam contra o Redis real em `localhost:6379`, que é exatamente o que AD-003 determina: o sandbox é dono da infraestrutura e as aplicações se conectam a ela. Redis é dependência de **runtime**, não de código nem de build, e portanto não viola ORG-02 — o que `StandaloneBoundaryTest` prova estruturalmente.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| ORG-03: a fronteira possui `settings.gradle`, build, wrapper e `gradle.properties` próprios | `StandaloneBoundaryTest.java:25-31` — `assertTrue(Files.isRegularFile(ROOT.resolve("settings.gradle")))` mais `build.gradle`, `gradle.properties`, `gradlew`, `gradle/wrapper/gradle-wrapper.jar` e `gradle-wrapper.properties` | os seis arquivos existem na própria raiz | ✅ |
| ORG-02: o build não lê build/fontes de outra raiz | `StandaloneBoundaryTest.java:36-38` — `assertFalse(build.contains("project("))` e `assertFalse(build.contains(".."))` | nenhuma dependência `project()` nem caminho que escape da raiz | ✅ |
| ORG-07: sem Kafka/Postgres/`common`/contratos nesta fronteira | `StandaloneBoundaryTest.java:44-48` — `assertFalse` para `kafka`, `postgres`, `avro`, `payment-contract` e `com.example.payments` no `build.gradle` | a fronteira é Redis-only, sem maquinaria de contrato | ✅ |
| ORG-02: as fontes não importam de outra fronteira | `StandaloneBoundaryTest.java:59` — `assertTrue(offenders.isEmpty(), ...)` varrendo todo `src/**.java` por `import` de `com.example.payments` ou `com.example.platform.featurecontrol` | nenhum arquivo importa de outra fronteira | ✅ |
| Done-when / MIG-02: os seis testes baseline sobrevivem ao build isolado | `AsyncRedisFlowIT.java:43-51` (`assertEquals("COMPLETED", body.status())`, `feeCents()==200`), `AsyncRedisFlowIT.java:63-64`, `AsyncBackpressureIT.java:63` (`throttled >= 1`), `AsyncDlqIT.java:50-51` (`xlen(DLQ) >= 1`), `AsyncRateLimiterUnitTest.java:23,33-36` | os mesmos 6 métodos passam pelo wrapper da fronteira | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `StandaloneBoundaryTest.java:25-31` | ORG-03 arquivos próprios da fronteira | ✅ |
| `StandaloneBoundaryTest.java:36-38` | ORG-02 sem build cross-root | ✅ |
| `StandaloneBoundaryTest.java:44-48` | ORG-07 sem concern de outra fronteira | ✅ |
| `StandaloneBoundaryTest.java:59` | ORG-02 sem fonte cross-root | ✅ |
| `AsyncRedisFlowIT.java:43-51,63-64` | MIG-02 equivalência funcional (baseline) | ✅ |
| `AsyncBackpressureIT.java:63`, `AsyncDlqIT.java:50-51`, `AsyncRateLimiterUnitTest.java:23,33-36` | MIG-02 equivalência funcional (baseline) | ✅ |

**Adequacy review:** cobertura suficiente e necessária para o escopo estrutural desta tarefa. `StandaloneBoundaryTest` replica o padrão já auditado em `payment-sbus`/`payment-api` para o mesmo problema e cada asserção falharia numa extração incompleta plausível: sem wrapper copiado, `ownsStandaloneBuildAndWrapper` falha; com um `implementation project(':common')` remanescente, `buildDeclaresNoCrossRootDependency` falha; se alguém reintroduzisse Avro/Kafka “só para reaproveitar o codec”, `buildDeclaresNoKafkaPostgresOrSharedCommonDependency` falha. `sourcesImportNothingFromAnotherBoundary` é o controle que fecha o buraco deixado pelos outros três: um `build.gradle` limpo com um `import com.example.payments...` numa classe passaria em todo o resto. Os 6 métodos baseline não foram tocados — a prova de equivalência de MIG-02 exige exatamente as asserções antigas rodando na raiz nova, e reescrevê-las destruiria o valor da comparação. Os gaps de comportamento que uma auditoria contra RED-01..08 já revela nesta implementação (polling devolve `UNKNOWN` para job em processamento, `MAXLEN ~` no `XADD`, consumer name fixo `worker-{i}` sem identidade de instância, `release` não atômico, `deadLetter` que engole exceção antes do ACK, off-by-one em `getRedeliveryCount() > maxDeliveries`) são **deliberadamente não corrigidos aqui**: cada um pertence por `Where` e por requisito a T39–T44 e será tratado tarefa a tarefa, com gate próprio. Não há SPEC_DEVIATION em T38.

#### T39: Persistir status, idempotência e segurança na aceitação

**Status:** Complete

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

**Gate evidence:** A aceitação herdada não persistia estado nenhum. `AsyncJobController.submit` gerava um `jobId`, chamava `queue.enqueue` e ia direto para o BRPOP; o único vestígio de um job era a chave de resultado, escrita **depois** do processamento. Isso produzia quatro lacunas reais contra RED-01/RED-08, todas confirmadas lendo o código de `7de39fb` antes de qualquer alteração. (1) **Polling mentia sobre trabalho aceito**: `GET /jobs/{id}` respondia `404 UNKNOWN` para um job aceito e ainda em processamento — indistinguível de um `jobId` que nunca existiu; um cliente que recebeu `202` e seguiu o `statusUrl` era informado de que seu trabalho não existe. (2) **Nada era gravado antes do enqueue**: mesmo que houvesse status, a ordem estava invertida, e RED-01 exige “persistir status consultável **antes** de enfileirar”. (3) **Não havia idempotência alguma**: um retry de cliente enfileirava um segundo job para o mesmo trabalho, e não existia `Idempotency-Key`, fingerprint, replay nem conflito. (4) **Não havia autenticação**: `POST /jobs` e `GET /jobs/{id}` eram anônimos, e não existia guarda que impedisse subir em produção nesse estado — exatamente o “claim intermediário” que RED-08 proíbe.

O pacote `api` novo fecha os quatro. `JobStatusStore.createProcessing` grava `job:<id>:status` e `JobAcceptanceService.accept` o faz **antes** de `enqueuer.enqueue`, com a ordem provada por um seam (`JobEnqueuer`) em vez de inferida. O layout de chaves separa deliberadamente `status` (TTL 24h) de `result` (TTL 15m): é a janela entre os dois que torna “terminou e o payload expirou” um fato respondível em vez de virar `UNKNOWN`, e `AsyncRedisProperties.validateRetention` recusa startup quando `status-ttl < result-ttl`, porque essa configuração destrói o estado `EXPIRED` silenciosamente. `markCompleted` usa `XX` + `KEEPTTL` — um job terminal não ressuscita uma aceitação já expirada nem estende a própria retenção. Idempotência replica o padrão auditado em T33 (`RedisStatusStore.reserve`): identidade e fingerprint entram como **um** valor sob um único `SET NX PX`, com o retry de duas tentativas para a corrida entre `NX` e `GET`; mesmo key + mesmo payload devolve o job original sem enfileirar nada, key diferente do payload é `409` determinístico. `JobFingerprint` delimita os campos com `|` e marca ausência com `\0`, porque sem delimitador `("ORDER-1", 10, "0")` e `("ORDER-1", 100, "")` concatenam para a mesma string e dois jobs de valores diferentes colidiriam. `ApiKeyFilter` fecha as duas rotas com `X-API-Key`, habilitado por padrão (mesmo default seguro de `payment-api`/T32), e `ProductionAcceptanceGuard` recusa `prod` sem AuthN, sem key, com a key default de desenvolvimento, sem idempotência obrigatória ou com admissão desligada.

Full gate (`async-redis-service/./gradlew test -PwithIT --no-daemon`) passou **39/39 testes, 0 falhas, 0 skipped**: os 10 de T38 mais 29 novos (`JobFingerprintUnitTest` 5, `JobAcceptanceServiceUnitTest` 2, `ProductionAcceptanceGuardUnitTest` 7, `JobPollingIT` 4, `JobIdempotencyIT` 6, `JobAcceptanceGatesIT` 5) — bem acima dos ≥12 pedidos. O build raiz roda os mesmos 39 verdes. Nenhum teste anterior foi removido, pulado ou enfraquecido; os três ITs baseline receberam apenas `@Property("async.redis.security.enabled"="false")`, porque testam fluxo e não autenticação, e todas as suas asserções seguem idênticas.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| RED-01: status é persistido **antes** do enqueue | `JobAcceptanceServiceUnitTest.java:78` — `assertEquals(new JobStatusView.Processing(), enqueuer.statusAtEnqueue.get(0))` observado dentro de `enqueue` | no instante do enqueue o job já é consultável | ✅ |
| RED-01: polling distingue *missing* | `JobPollingIT.java:53-54` — `assertEquals(HttpStatus.NOT_FOUND, ...)` e `status() == "UNKNOWN"` | job nunca aceito é 404 UNKNOWN | ✅ |
| RED-01: polling distingue *processing* | `JobPollingIT.java:67-69` — `assertEquals(HttpStatus.ACCEPTED, ...)`, `status() == "PROCESSING"`, `assertNull(result())` contra worker de 600ms e wait de 200ms | job em voo é 202 PROCESSING, sem resultado inventado | ✅ |
| RED-01: polling distingue *terminal* | `JobPollingIT.java:79-84` — `HttpStatus.OK`, `"COMPLETED"`, `reference()=="POLL-COMPLETED"`, `amountCents()==10_000`, `feeCents()==200` | terminal devolve o resultado real, campo a campo | ✅ |
| RED-01: polling distingue *expired* | `JobPollingIT.java:96-99` — `assertEquals(HttpStatus.GONE, ...)` e `status() == "EXPIRED"` com `result-ttl=1s` e `status-ttl=30s` | resultado expirado é 410 EXPIRED, não 404 UNKNOWN | ✅ |
| RED-08: replay devolve o job original (happy) | `JobIdempotencyIT.java:57` — `assertEquals(first, second)` para mesma key e mesmo payload | retry não cria identidade nova | ✅ |
| RED-08: replay não enfileira segundo job | `JobIdempotencyIT.java:70` — `assertEquals(1L, after - before)` sobre `XLEN` real do stream | idempotência é sobre efeito, não só sobre resposta | ✅ |
| RED-08: mesma key com payload diferente é conflito (error) | `JobIdempotencyIT.java:84-87` — `HttpStatus.CONFLICT`, `status()=="CONFLICT"` e `jobId()` igual ao original | conflito determinístico que nomeia o dono da key | ✅ |
| RED-08: conflito não enfileira nada | `JobIdempotencyIT.java:98-99` — `assertEquals(afterOriginal, conn.sync().xlen(STREAM))` | submissão rejeitada não chega ao stream | ✅ |
| RED-08: keys distintas não se confundem (edge) | `JobIdempotencyIT.java:110` — `assertNotEquals(first, second)` com payload idêntico | dedup é por key, não por payload | ✅ |
| RED-08: fingerprint é estável e discriminante | `JobFingerprintUnitTest.java:22,31,40,47,58` — `assertEquals` para payloads idênticos; `assertNotEquals` para valor, referência, nota ausente vs vazia e deslocamento entre campos adjacentes | mesma submissão = mesmo hash; qualquer diferença de negócio = hash diferente | ✅ |
| RED-08: AuthN obrigatória — sem credencial | `JobAcceptanceGatesIT.java:49` — `assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus())` | POST anônimo é recusado | ✅ |
| RED-08: AuthN obrigatória — credencial inválida | `JobAcceptanceGatesIT.java:59` — `assertEquals(HttpStatus.UNAUTHORIZED, ...)` | key desconhecida não passa | ✅ |
| RED-08: AuthN cobre também o polling | `JobAcceptanceGatesIT.java:68` — `assertEquals(HttpStatus.UNAUTHORIZED, ...)` no `GET` | consulta de estado não é rota aberta | ✅ |
| RED-08: credencial válida é aceita (happy) | `JobAcceptanceGatesIT.java:79-80` — `assertEquals("PROCESSING", accepted.status())` e `assertNotNull(accepted.jobId())` | o gate autentica, não bloqueia tudo | ✅ |
| RED-08: idempotência obrigatória no profile gateado | `JobAcceptanceGatesIT.java:90-92` — `HttpStatus.BAD_REQUEST` e `status()=="IDEMPOTENCY_KEY_REQUIRED"` | com o gate ligado, submissão sem key é recusada com motivo | ✅ |
| RED-08: produção sem AuthN não sobe | `ProductionAcceptanceGuardUnitTest.java:26-28` — `assertThrows(ConfigurationException.class, ...)` e mensagem citando `async.redis.security.enabled` | gate ausente para o startup, não degrada | ✅ |
| RED-08: produção recusa key default/ausente/em branco | `ProductionAcceptanceGuardUnitTest.java:33,40-42,47` — `assertThrows` para lista vazia, key de desenvolvimento (mensagem contém `development default`) e key em branco | credencial de exemplo não vira credencial de produção | ✅ |
| RED-08: produção exige idempotência e admissão | `ProductionAcceptanceGuardUnitTest.java:54-56,62-64` — `assertThrows` com mensagens citando `idempotency-required` e `admission-limit-per-sec` | os três gates de RED-08 são exigidos juntos | ✅ |
| RED-08: configuração completa sobe | `ProductionAcceptanceGuardUnitTest.java:69` — `assertDoesNotThrow(...)` | a guarda discrimina, não recusa tudo | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `JobAcceptanceServiceUnitTest.java:78,88-90` | RED-01 ordem status→enqueue e identidade por submissão sem key | ✅ |
| `JobPollingIT.java:53-54,67-69,79-84,96-99` | RED-01 quatro estados de polling | ✅ |
| `JobIdempotencyIT.java:57,70,84-87,98-99,110,119` | RED-08 replay, conflito e efeito no stream | ✅ |
| `JobFingerprintUnitTest.java:22,31,40,47,58` | RED-08 identidade de payload | ✅ |
| `JobAcceptanceGatesIT.java:49,59,68,79-80,90-92` | RED-08 AuthN e idempotência obrigatória fim a fim | ✅ |
| `ProductionAcceptanceGuardUnitTest.java:26-69` | RED-08 “produção ou exemplo, sem meio-termo” | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Cada asserção de payload verifica valor ou estado observável — o status HTTP, o `status()` do corpo, os campos do `JobResult` e o `XLEN` real do stream — nunca apenas que um método foi chamado. Os quatro estados de polling são testados **separadamente** porque a AC os nomeia separadamente e é justamente o colapso entre dois deles que existia antes: uma implementação que devolvesse `UNKNOWN` para job em voo passa em `aJobThatWasNeverAcceptedIsUnknown` e falha em `anAcceptedJobIsProcessingWhileItIsStillInFlight`; uma que devolvesse `UNKNOWN` para resultado expirado passa nos três primeiros e falha em `aFinishedJobWhoseResultAgedOutIsExpiredNotUnknown`. `aReplayQueuesNoSecondJob` e `aConflictQueuesNothing` são os controles que impedem uma idempotência “de fachada”: uma implementação que devolvesse o `jobId` original mas enfileirasse mesmo assim passaria em `sameKeyAndPayloadReturnsTheOriginalJob` e falharia aqui. `adjacentFieldsCannotBeShiftedIntoTheSameFingerprint` usa uma colisão real (`"ORDER-1"+"100"` por dois caminhos), não hipotética. `aFullyGatedProductionConfigurationStarts` é o controle negativo da guarda: sem ele, uma guarda que lançasse sempre passaria em todos os outros seis. `distinctKeysGetDistinctJobs` fecha o buraco simétrico — uma implementação que ignorasse a key e sempre replayasse passaria em quase todo o resto. O `JobResponse.jobId`/`statusUrl` passaram a `@Nullable` porque a rejeição por key ausente não tem job para nomear; inventar um id nesse corpo seria pior. Pool de espera, `maxWait`/`maxTotal` e o contrato 429/202 sob saturação seguem explicitamente com T40 (RED-02/CAP-03), que os possui por `Where` e por requisito; esta tarefa não alterou `RedisConnections` nem o limiter. Não há SPEC_DEVIATION em T39.

#### T40: Limitar pool de espera e admissão

**Status:** Complete

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

**Gate evidence:** A espera herdada tinha três defeitos contra RED-02, todos confirmados lendo o código antes de alterar. (1) **O orçamento começava depois do borrow**: `awaitResult` chamava `redis.borrowBlocking()` e só então media `wait-timeout` no BRPOP, de modo que um pool saturado permitia gastar o timeout inteiro adquirindo a conexão e o timeout inteiro de novo no pop — o dobro do que o cliente foi prometido. (2) **A aquisição não tinha timeout algum**: `pool().borrowObject()` sem `maxWait` configurado bloqueia para sempre quando o pool está esgotado, que é exatamente o "bloquear além do orçamento HTTP" que a AC proíbe. (3) **Saturação era indistinguível de lentidão**: `awaitResult` devolvia `Optional.empty()` tanto para "sem resultado ainda" quanto para "sem capacidade de esperar", e ambos viravam um `202` idêntico, escondendo do operador o único sinal que diferencia um worker lento de um serviço saturado.

Corrigir (1) e (2) expôs um quarto defeito, **este introduzido pela própria correção** e encontrado com o gate vermelho, não por inspeção. Passar um deadline para o pool exige uma sobrecarga temporizada de `borrowObject`, e `ConnectionPoolSupport` do Lettuce 6.4.0 sobrescreve **apenas** o `borrowObject()` sem argumentos para embrulhar a conexão (verificado no bytecode: `ConnectionPoolSupport$1` declara só `borrowObject()`, e o `createGenericObjectPool` de 2 argumentos passa `wrapConnections=true` via `iconst_1`). As sobrecargas `borrowObject(long)` e `borrowObject(Duration)` devolvem a conexão **crua**, cujo `close()` fecha o socket em vez de devolvê-lo ao pool. O diagnóstico foi direto: `active-after-borrow=1`, `close()`, `active-after-close=1` — capacidade perdida de forma permanente, uma vaga por espera, até o pool não servir mais nada. Com `pool-max-total=1` os cinco testes do budget falharam com `NoCapacity`; num pool default de 64 o mesmo vazamento passaria despercebido em teste e mataria o serviço em produção. A correção é `RedisConnections.WaitLease`: a devolução passou a ser explícita (`returnObject`), nunca por `close()` da conexão, e uma espera que falhou no meio do protocolo marca `invalidate()` para destruir o socket em vez de reciclá-lo para o próximo waiter. O `finally` que fecha o lease é obrigatório porque try-with-resources fecha **antes** do `catch`, o que tornaria o `invalidate()` inócuo.

`pool-max-wait` ganhou semântica própria em vez de virar configuração morta: a aquisição é limitada a `min(orçamento restante, pool-max-wait)`, para que um pool saturado não consuma o orçamento inteiro na fila por conexão e deixe zero tempo para o pop que o cliente está pagando. `validateRetention` recusa startup com `pool-max-total <= 0` ou `pool-max-wait` não positivo — um pool sem capacidade ou sem timeout finito não tem backpressure para aplicar. O contrato HTTP separa as duas recusas: `429` é admissão negada (nada foi enfileirado, já coberto por `AsyncBackpressureIT`) e `202` + `X-Backpressure: wait-pool-exhausted` + `Retry-After` é trabalho aceito cuja espera foi descartada. Devolver `429` no segundo caso seria mentir sobre uma submissão que já está no stream.

Full gate (`async-redis-service/./gradlew test -PwithIT --no-daemon`, Redis real em `localhost:6379`) passou **52/52 testes, 0 falhas, 0 skipped**: os 39 de T38–T39 mais 13 novos (`JobWaitBudgetIT` 6, `AsyncRedisPropertiesUnitTest` 4, `JobBackpressureContractIT` 2, `JobWaitAcquisitionCapIT` 1), acima dos ≥8 pedidos. Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| RED-02: espera termina dentro do próprio orçamento | `JobWaitBudgetIT.java:88-90` — `assertInstanceOf(TimedOut.class, ...)`, `elapsed >= BUDGET_MS*0.8` e `elapsed < BUDGET_MS*2` | a espera gasta o orçamento, nem menos nem mais | ✅ |
| RED-02: o orçamento cobre aquisição, não só o BRPOP | `JobWaitBudgetIT.java:121-123` — `assertInstanceOf(TimedOut.class, ...)` e `elapsed < BUDGET_MS*1.4` com a única conexão presa por 600ms | 600ms de fila + pop ≈ 1 orçamento, não 1,6 | ✅ |
| RED-02: pool saturado descarta em vez de enfileirar | `JobWaitBudgetIT.java:104-105` — `assertInstanceOf(NoCapacity.class, ...)` e `elapsed < BUDGET_MS*1.5` com a conexão presa por 3s | saturação vira recusa no orçamento, não bloqueio | ✅ |
| RED-02: timeout de aquisição é finito e próprio | `JobWaitAcquisitionCapIT.java:62-66` — `NoCapacity`, `elapsed >= 100ms` e `elapsed < 1500ms` com `pool-max-wait=200ms` e `wait-timeout=3s` | a aquisição para em `pool-max-wait`, não no orçamento inteiro | ✅ |
| Done-when: não cresce ilimitado (capacidade nunca excedida) | `JobWaitBudgetIT.java:150-152` — `highWaterMark <= capacity` amostrado sob 6 esperas concorrentes e `assertEquals(0, redis.borrowedConnections())` | concorrência nunca ultrapassa `pool-max-total` | ✅ |
| Done-when: não cresce ilimitado (capacidade é devolvida) | `JobWaitBudgetIT.java:66-67,73-79` — `borrowedConnections()==0` após cada uma de 3 esperas e `Released` com `reference()=="REUSE-1"`, `amountCents()==4_000`, `feeCents()==80` na quarta | capacidade sobrevive ao uso repetido | ✅ |
| RED-02: configuração sem capacidade não sobe | `AsyncRedisPropertiesUnitTest.java:31-32,42-43` — `assertThrows(ConfigurationException.class, props::validateRetention)` e mensagem contendo `async.redis.pool-max-total`, para `0` e `-1` | pool sem capacidade é recusado no startup | ✅ |
| RED-02: configuração sem timeout finito não sobe | `AsyncRedisPropertiesUnitTest.java:53-54,64-65` — `assertThrows(...)` e mensagem contendo `async.redis.pool-max-wait`, para `ZERO` e `-1ms` | aquisição sem timeout positivo é recusada no startup | ✅ |
| CAP-03: saturação responde `202` com backpressure explícito | `JobBackpressureContractIT.java:62-71` — `HttpStatus.ACCEPTED`, `header("X-Backpressure")=="wait-pool-exhausted"`, `header("Retry-After")=="1"`, `status()=="PROCESSING"`, `statusUrl()=="/jobs/"+jobId` e `assertNull(result())` | contrato de backpressure completo, campo a campo | ✅ |
| CAP-03: backpressure descarta a espera, nunca o trabalho | `JobBackpressureContractIT.java:93-100` — `HttpStatus.OK`, `"COMPLETED"`, `reference()=="BP-2"`, `amountCents()==5_000`, `feeCents()==100`, `status()=="PROCESSED"` no polling | o `202` é honesto: o job enfileirado termina | ✅ |
| CAP-03: admissão excedida responde `429` | `AsyncBackpressureIT.java:64-66` (pré-existente) — `assertTrue(throttled.get() >= 1)` sob limite de 1/s | recusa de admissão é `429`, não `202` | ✅ |
| Done-when: resultado liberado chega ao waiter (controle) | `JobWaitBudgetIT.java:51-56` — `Released` com `jobId`, `reference()=="BUDGET-1"`, `amountCents()==10_000`, `feeCents()==200`, `status()=="PROCESSED"` | o pool limitado continua entregando resultados | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `JobWaitBudgetIT.java:51-56` | Done-when controle: espera limitada ainda entrega | ✅ |
| `JobWaitBudgetIT.java:66-67,73-79` | Done-when "não cresce ilimitado" (devolução de capacidade) | ✅ |
| `JobWaitBudgetIT.java:88-90,121-123` | RED-02 orçamento único cobrindo aquisição + pop | ✅ |
| `JobWaitBudgetIT.java:104-105` | RED-02 descarte sob saturação | ✅ |
| `JobWaitBudgetIT.java:150-152` | Done-when capacidade nunca excedida sob concorrência | ✅ |
| `JobWaitAcquisitionCapIT.java:62-66` | RED-02 `maxWait` como timeout de aquisição próprio | ✅ |
| `JobBackpressureContractIT.java:62-71,93-100` | CAP-03 contrato `202` + backpressure e durabilidade do job | ✅ |
| `AsyncRedisPropertiesUnitTest.java:31-32,42-43,53-54,64-65` | RED-02 `maxTotal`/`maxWait` como invariante de startup | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção de payload verifica valor ou estado observável — status HTTP, headers nomeados, campos do `JobResult` e a contagem real de conexões do pool (`getNumActive()`) — nunca apenas que um método foi chamado. Os três testes de tempo são deliberadamente distintos porque falham por motivos diferentes: `aWaitWithNoResultEndsInsideItsOwnBudget` tem limite inferior **e** superior, então uma implementação que devolvesse `TimedOut` imediatamente falharia nele e passaria nos outros; `theBudgetCoversAcquisitionAndNotOnlyTheBrpop` é o único que mata a versão antiga, em que aquisição e pop tinham orçamentos separados; e `acquisitionIsCappedByPoolMaxWaitAndNotByTheWholeBudget` mata uma implementação que ignorasse `pool-max-wait` e usasse sempre o orçamento restante — sem ele, `pool-max-wait` seria configuração decorativa. `everyWaitReturnsItsConnectionSoCapacitySurvivesRepeatedUse` é o teste que encontrou o vazamento do `borrowObject` não embrulhado: com o código anterior, a espera 1 já falhava com `NoCapacity`. `aJobShedByWaitCapacityIsStillProcessedToCompletion` é o controle que impede um backpressure "de fachada": uma implementação que respondesse `202` e descartasse o job passaria em `anExhaustedWaitPoolAnswers202WithExplicitBackpressure` e falharia aqui. Os quatro testes de configuração cobrem os dois limites de cada propriedade (zero e negativo) porque `isNegative()` e `isZero()` são checagens separadas e uma sozinha deixaria metade do buraco aberto. `AsyncBackpressureIT` cobre o `429` de admissão e não foi duplicado. O happy path HTTP `200 COMPLETED` já é coberto por `AsyncRedisFlowIT.java:44-52` e também não foi duplicado. Identidade única de consumidor, reconexão com backoff e readiness seguem com T41 (RED-04/RED-05); release atômico com T42; DLQ e off-by-one com T43; e retenção PEL-safe com T44 — nenhum deles foi tocado aqui. Não há SPEC_DEVIATION em T40.

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
**Status:** Complete

**Gate evidence:** Esta tarefa retomou um checkpoint não-terminado (`1a59426`). O checkpoint já continha `JobWorker`, `WorkerIdentity`, `ReclaimCoordinator`, `WorkerReadiness`/`WorkerReadinessIndicator` e 5 arquivos de teste no pacote `worker/`, mas nunca tinha passado o gate. Reconciliação por evidência (não pela narrativa do handoff): `./gradlew compileJava` falhou com `cannot find symbol: policyApplied` em `RedisConnections.java:62,64,69` — um campo referenciado por `client()` mas nunca declarado. Investigação adicional mostrou que `client()` (o método que aplica `autoReconnect(false)` + `REJECT_COMMANDS`, exigido por RED-05) nunca era chamado por `shared()`, `pool()` ou `dedicated()`, que usavam `client.connect()` diretamente — a política de reconexão nunca teria sido aplicada mesmo sem o erro de compilação. Decisão: **fix forward**, não revert. O design e os testes do checkpoint estavam corretos e bem alinhados a RED-04/RED-05; o defeito era local e mecânico (~5 linhas), não estrutural. Correção: adicionado o campo `private volatile boolean policyApplied` e as três chamadas de conexão (`shared()`, `pool()` via factory, `dedicated()`) passaram a obter o cliente por `client()` em vez de usar o campo `client` bruto (`RedisConnections.java:38-46,79-87,119-121,142`).

Com o build compilando, o full gate revelou uma segunda falha real, desta vez no teste, não no código de produção: `WorkerConsumerIdentityIT` (2 testes) assumia que um worker aparece em `XINFO CONSUMERS` só por chamar `XREADGROUP` com `BLOCK`. Verificado empiricamente contra o Redis real de `localhost:6379` (Redis 7.0.15, via `redis-cli`) que isso é falso: um `XREADGROUP` bloqueante que expira sem novas entradas não registra o consumidor; o registro só ocorre quando ao menos uma entrega de fato acontece. O teste testava uma premissa errada sobre a API do Redis, não o comportamento do `JobWorker`. Corrigido publicando entregas reais (`pushDummyJobs`, `WorkerConsumerIdentityIT.java:110-119`) antes de aguardar o registro, com volume (40 e 120 mensagens, respectivamente) folgado o bastante acima do teto de 16 por leitura para que nenhum worker consiga drenar tudo antes do outro receber sua primeira entrega — a assinatura da asserção (verificar `XINFO CONSUMERS` real) não mudou.

Full gate (`async-redis-service/./gradlew test -PwithIT --no-daemon`, Redis real em `localhost:6379`) passou **68/68 testes, 0 falhas, 0 skipped**: os 52 de T38–T40 mais 16 novos do pacote `worker/` (`WorkerIdentityUnitTest` 5, `WorkerConsumerIdentityIT` 2, `ReclaimCoordinatorIT` 6, `WorkerRecoveryIT` 3), muito acima dos ≥9 pedidos. Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| RED-04: workers do mesmo processo nunca colidem | `WorkerIdentityUnitTest.java:31-35` — `assertEquals(8, names.size(), ...)` para 8 índices de worker | 8 índices produzem 8 nomes distintos | ✅ |
| RED-04: duas instâncias nunca colidem no nome | `WorkerIdentityUnitTest.java:44-46` — `assertNotEquals(first.instanceId(), second.instanceId())` e `assertNotEquals` para `consumerName(0)`/`consumerName(1)` | identidades e nomes de consumidor de instâncias diferentes nunca coincidem | ✅ |
| RED-04: identidade estável (não regenerada a cada poll) | `WorkerIdentityUnitTest.java:54-55` — `assertEquals(identity.consumerName(0), identity.consumerName(0))` e `assertEquals(identity.instanceId(), identity.instanceId())` | o nome do consumidor não muda entre chamadas | ✅ |
| RED-04: instance-id configurado é usado verbatim | `WorkerIdentityUnitTest.java:62-64` — `assertEquals("pod-7", identity.instanceId())`, `assertEquals("pod-7-w0", ...)`, `assertEquals("pod-7-w3", ...)` | configuração explícita vira o nome exato | ✅ |
| RED-04: instance-id em branco cai para um derivado | `WorkerIdentityUnitTest.java:71-72` — `assertTrue(identity.instanceId().length() > 1, ...)` e `assertNotEquals` entre duas derivações | config em branco nunca vira identidade vazia ou colidente | ✅ |
| RED-04: cada worker de uma instância se registra sob seu próprio nome (evidência real do Redis) | `WorkerConsumerIdentityIT.java:82-83` — `assertTrue(names.contains("alpha-w0"), ...)` e `assertTrue(names.contains("alpha-w1"), ...)` lendo `XINFO CONSUMERS` real | `XINFO CONSUMERS` mostra os dois workers da instância, não um nome compartilhado | ✅ |
| RED-04: duas instâncias registram quatro consumidores, não colapsam em dois | `WorkerConsumerIdentityIT.java:101-105` — `assertTrue(names.containsAll(expected))` com `expected = {inst-one-w0, inst-one-w1, inst-two-w0, inst-two-w1}` | `XINFO CONSUMERS` mostra 4 nomes distintos para 2 instâncias | ✅ |
| RED-04: só um worker por vez tem o turno de reclaim | `ReclaimCoordinatorIT.java:63,66` — `assertTrue(coordinator.claimTurn("worker-a"))`, `assertFalse(coordinator.claimTurn("worker-b"))`, `assertEquals("worker-a", coordinator.currentOwner())` | um segundo scanner concorrente é recusado enquanto o primeiro segura o turno | ✅ |
| RED-04: o dono renova o turno em vez de perdê-lo a cada ciclo | `ReclaimCoordinatorIT.java:74-76` — `assertTrue(coordinator.claimTurn("worker-a"))` (segunda chamada), `assertEquals("worker-a", ...)`, `assertFalse(coordinator.claimTurn("worker-b"))` | renovação mantém o mesmo dono | ✅ |
| RED-04: um turno liberado passa para o próximo worker | `ReclaimCoordinatorIT.java:85-87` — `assertNull(coordinator.currentOwner(), ...)`, `assertTrue(coordinator.claimTurn("worker-b"), ...)`, `assertEquals("worker-b", ...)` | liberação explícita libera o turno de fato | ✅ |
| RED-04: um não-dono não consegue liberar o turno alheio | `ReclaimCoordinatorIT.java:97-98` — `assertEquals("worker-a", coordinator.currentOwner())` após `releaseTurn("worker-b")`, `assertFalse(coordinator.claimTurn("worker-b"))` | fencing: só o dono libera seu próprio turno | ✅ |
| RED-04: turno de um dono travado expira e libera o reclaim | `ReclaimCoordinatorIT.java:108-109` — `assertTrue(coordinator.claimTurn("worker-b"), ...)`, `assertEquals("worker-b", coordinator.currentOwner())` após o lease expirar | um dono que nunca renova não trava o reclaim para sempre | ✅ |
| RED-04: um dono expirado não ressuscita o turno sobre o sucessor | `ReclaimCoordinatorIT.java:119-120` — `assertFalse(coordinator.claimTurn("worker-a"), ...)`, `assertEquals("worker-b", coordinator.currentOwner())` | um dono expirado não recupera o turno do sucessor | ✅ |
| RED-05: Redis indisponível no startup mantém readiness down e o worker retenta | `WorkerRecoveryIT.java:62-65` — `assertFalse(readiness.hasConsumingCapacity())`, `assertEquals(0, readiness.consumingWorkers())`, `assertEquals(HealthStatus.DOWN, readinessStatus(ctx))` | readiness fica DOWN enquanto Redis está fora, sem crash do worker | ✅ |
| RED-05: readiness sobe quando a capacidade de consumo volta | `WorkerRecoveryIT.java:71-74` — `assertTrue(readiness.hasConsumingCapacity(), ...)`, `assertEquals(1, readiness.consumingWorkers())`, `assertEquals(HealthStatus.UP, readinessStatus(ctx))` | readiness reflete a capacidade real assim que o worker volta a consumir | ✅ |
| RED-05: um worker recuperado volta a processar jobs (não só a se reconectar) | `WorkerRecoveryIT.java:96-102` — `assertNotNull(result, ...)`, `assertEquals(jobId, ...)`, `assertEquals("RECOVERED-1", result.reference())`, `assertEquals(3_000L, result.amountCents())`, `assertEquals(60L, result.feeCents())`, `assertEquals("PROCESSED", result.status())` | readiness UP corresponde a trabalho de fato concluído, campo a campo | ✅ |
| RED-05: readiness cai de novo quando Redis some no meio do loop | `WorkerRecoveryIT.java:122-124` — `assertFalse(readiness.hasConsumingCapacity(), ...)`, `assertEquals(HealthStatus.DOWN, readinessStatus(ctx))` após `gate.shut()` | uma queda de Redis em produção derruba readiness de novo, não só no startup | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `WorkerIdentityUnitTest.java:31-35,44-46,54-55,62-64,71-72` | RED-04 unicidade/estabilidade/derivação de identidade | ✅ |
| `WorkerConsumerIdentityIT.java:82-83,101-105` | RED-04 identidade observada no registro real do Redis | ✅ |
| `ReclaimCoordinatorIT.java:63-120` (6 testes) | RED-04 coordenação de um único scanner de reclaim | ✅ |
| `WorkerRecoveryIT.java:62-65,71-74,96-102,122-124` | RED-05 readiness + reconexão com backoff + retomada de processamento | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção de payload verifica estado observável — nomes reais no `XINFO CONSUMERS` do Redis, o dono real de uma chave de lease, o `HealthStatus` real do indicador de readiness e os campos do `JobResult` processado — nunca só que um método foi chamado. `aRecoveredWorkerProcessesJobsAgain` é o teste de controle que impede uma "readiness de fachada": uma implementação que marcasse `markConsuming` sem o worker realmente ler do grupo passaria nos dois primeiros testes de `WorkerRecoveryIT` e falharia aqui, porque o job nunca seria processado. `aLapsedOwnerCannotResurrectItsTurnOverTheNewOwner` mata especificamente um `RENEW_IF_OWNER` que checasse só a chave e não o valor do dono. Os dois testes de `WorkerConsumerIdentityIT` usam `RedisGate`/conexão real, não mocks, porque a AC exige unicidade "observada onde importa": o próprio registro do Redis. Nenhum teste testa comportamento do driver Lettuce ou do framework Awaitility isoladamente. Liberação atômica de resultado segue com T42; DLQ e off-by-one com T43; retenção PEL-safe com T44 — nenhum deles foi tocado aqui. SPEC_DEVIATION: nenhuma no comportamento observável; o único desvio foi corrigir a premissa de `WorkerConsumerIdentityIT` sobre quando o Redis registra um consumidor, documentado no Javadoc do teste e nesta evidência.

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
**Status:** Complete

**Gate evidence:** `JobQueue.release` fazia quatro round trips separados ao Redis: `PSETEX` do resultado, `JobStatusStore.markCompleted` (outro `SET`), `LPUSH` do wakeup e `PEXPIRE` da lista. Uma conexão perdida entre dois desses passos deixava o job inconsistente — o caso mais visível é um resultado já persistido de forma durável com o status preso em `PROCESSING` para sempre, porque `JobStatusStore.find` confia no status, não no resultado, para saber que um job terminou. Como o worker só dá ACK depois que `release` retorna sem lançar (`JobWorker.handle`), qualquer falha nesse meio deixava a mensagem no PEL para ser redistribuída — o que chama `release` de novo para o mesmo job, e o `LPUSH` incondicional duplicava a entrada de wakeup a cada nova tentativa.

Criado `result/ResultReleaser` com um único script Lua (`EVAL`) cobrindo os quatro efeitos: `SET` do resultado com TTL, `SET XX KEEPTTL` do status (mesma semântica de `JobStatusStore.markCompleted`, nunca ressuscita um job nunca aceito), e um `LPUSH`+`PEXPIRE` do wakeup condicionado a um `SET NX` de uma chave-marcador (`JobKeys.responseSent`) que só é `true` na primeira execução bem-sucedida. Um `EVAL` roda como um passo indivisível no Redis: não há janela em que uma conexão derrubada no meio do cliente observe ou deixe um release parcialmente aplicado. `JobQueue.release` passou a delegar para `ResultReleaser` (a dependência em `JobStatusStore` foi removida de `JobQueue`, que não a usava para mais nada); `JobWaitBudgetIT` e `JobWorker`, os dois outros chamadores de `queue.release(...)`, não precisaram mudar.

Full gate (`async-redis-service/./gradlew test -PwithIT --no-daemon`, Redis real em `localhost:6379`) passou **76/76 testes, 0 falhas, 0 skipped**: os 68 de T38–T41 mais 8 novos em `ResultReleaserIT`, acima dos ≥8 pedidos. Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| RED-06: o resultado é persistido de forma durável | `ResultReleaserIT.java:96-100` — `assertEquals(toJson(result), stored, ...)` lendo `GET` direto da chave de resultado | o conteúdo gravado é exatamente o resultado liberado | ✅ |
| RED-06: o status vira terminal junto com o resultado | `ResultReleaserIT.java:105-110` — `assertTrue(raw != null && raw.contains("\"COMPLETED\""), ...)` | status observável muda para `COMPLETED` no mesmo release | ✅ |
| RED-06 (guarda de correção): release nunca ressuscita status de job nunca aceito | `ResultReleaserIT.java:114-122` — `assertNull(conn.sync().get(JobKeys.status(jobId)), ...)` e, na mesma chamada, `assertEquals(toJson(result), ...)` no resultado | resultado ainda é persistido; status permanece ausente (XX) | ✅ |
| RED-06: wakeup libera o waiter (persistence + wakeup) | `ResultReleaserIT.java:126-133` — `assertEquals(List.of(toJson(result)), entries)` via `LRANGE` real | exatamente uma entrada, com o payload correto, na lista de wakeup | ✅ |
| RED-06: TTL é coerente | `ResultReleaserIT.java:137-144` — `assertTrue(pttl > 0 && pttl <= props.getResultTtl().toMillis(), ...)` via `PTTL` real | TTL da lista de wakeup é positivo e nunca excede a janela configurada | ✅ |
| RED-06: redelivery é idempotente (sem duplicar wakeup) | `ResultReleaserIT.java:148-157` — dois `release(result)` seguidos, depois `assertEquals(1, entries.size(), ...)` | uma segunda liberação do mesmo job não duplica a entrada de wakeup | ✅ |
| RED-06: redelivery é idempotente (resultado e status coerentes) | `ResultReleaserIT.java:161-172` — dois `release(result)`, depois `assertEquals(toJson(result), ...)` e `assertTrue(status.contains("\"COMPLETED\""), ...)` | conteúdo e status permanecem corretos após uma segunda liberação | ✅ |
| RED-06: falhas em cada etapa não perdem resultado (atomicidade sob concorrência) | `ResultReleaserIT.java:176-198` — 8 releases concorrentes do mesmo job, depois `assertEquals(1, entries.size(), ...)` | o `EVAL` serializa liberações concorrentes em exatamente um wakeup, nunca uma corrida parcial | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `ResultReleaserIT.java:96-100` | RED-06 persistência durável do resultado | ✅ |
| `ResultReleaserIT.java:105-110` | RED-06 transição de status para terminal | ✅ |
| `ResultReleaserIT.java:114-122` | RED-06 guarda XX (nunca ressuscita status inexistente) | ✅ |
| `ResultReleaserIT.java:126-133` | RED-06 wakeup exato, campo a campo | ✅ |
| `ResultReleaserIT.java:137-144` | RED-06 TTL coerente e limitado | ✅ |
| `ResultReleaserIT.java:148-157,161-172` | RED-06 redelivery idempotente (wakeup e conteúdo) | ✅ |
| `ResultReleaserIT.java:176-198` | RED-06 atomicidade sob concorrência | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção de payload lê o estado real do Redis — `GET`, `LRANGE`, `PTTL` — nunca apenas que um método foi chamado; nenhuma delegação `JobQueue -> ResultReleaser` foi testada por contagem de chamadas, porque isso provaria só a fiação, não o efeito no Redis que a AC exige (as ITs existentes de `JobQueue`, como `JobWaitBudgetIT.aReleasedResultIsReturnedToTheWaiter`, já exercitam o caminho público sem duplicação). `releaseNeverResurrectsAJobThatWasNeverAccepted` é o teste de controle que mata uma implementação que trocasse `XX` por incondicional: ela passaria em `releaseMarksAnAcceptedJobCompleted` e falharia aqui. `concurrentRedeliveredReleasesStillWakeUpExactlyOnce` mata especificamente uma versão que movesse a checagem do marcador para fora do script (uma corrida entre `EXISTS` e `SET` no lado do cliente duplicaria o wakeup sob concorrência real, o que o teste sequencial de redelivery não detectaria sozinho). `releaseSetsATtlOnTheWakeupList` tem limite inferior e superior porque uma implementação que nunca expirasse a lista, ou que a expirasse imediatamente, passaria num teste com um único lado. Nenhum teste verifica comportamento do driver Lettuce ou do Awaitility isoladamente. Poison/DLQ e off-by-one seguem com T43; retenção PEL-safe com T44 — nenhum deles foi tocado aqui. Sem SPEC_DEVIATION: o script cobre os quatro efeitos que design.md 4.5 descreve ("Resultado, wakeup e TTL usam Lua idempotente"), incluindo o status porque deixá-lo fora do `EVAL` reabriria exatamente a janela de inconsistência que esta tarefa existe para fechar.

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
**Status:** Complete

**Gate evidence:** Três defeitos contra RED-07, todos confirmados lendo `JobWorker` antes de alterar. (1) **Off-by-one no limite de entregas:** `reclaim` usava `pm.getRedeliveryCount() > maxDeliveries`; como cada `XCLAIM` já é uma tentativa de processamento, esse `>` deixava passar uma redelivery a mais do que o configurado — com `max-deliveries=5`, o job era processado 6 vezes, não 5. (2) **ACK silencioso sobre payload inválido:** `handle` confirmava (`XACK`) qualquer mensagem sem `jobId` imediatamente, sem nunca gravar DLQ — exatamente o "confirmar jobId/payload inválido silenciosamente" que a AC proíbe. (3) **ACK sem confirmação de DLQ:** em `deadLetter`, se `XRANGE` não encontrasse mais o corpo original (`msgs.isEmpty()`), o código pulava o `XADD` para a DLQ mas chamava `XACK` do mesmo jeito — um item poderia ser confirmado sem nunca ter sido gravado na DLQ.

Criado `dlq/DeadLetterWriter`, injetado em `JobWorker`. `exceedsMaxDeliveries(redeliveryCount)` centraliza o limite corrigido (`>=`, não `>`) e é testável sem Redis. `write(...)` grava o corpo original mais o motivo (`dlqReason`) na stream de DLQ. `JobWorker` ganhou dois caminhos: `handle` agora valida `jobId` (presente e não vazio) e `amountCents` (presente, parseável, não negativo) **antes** de processar — payload estruturalmente inválido nunca se beneficia de retry, então é gravado na DLQ com o motivo (`missing-job-id`, `missing-amount`, `invalid-amount`, `negative-amount`) e confirmado só depois; `reclaim` usa o limite corrigido e `deadLetterExceeded` sempre grava algo na DLQ antes do ACK, mesmo no caso raro em que o corpo original não é mais encontrado (um corpo mínimo com o id da stream substitui o corpo ausente, em vez de pular a gravação). Os dois caminhos de dead-letter só confirmam depois que a escrita na DLQ retorna sem lançar; uma falha na escrita deixa a mensagem pendente, recuperável, para a próxima tentativa de reclaim.

Full gate (`async-redis-service/./gradlew test -PwithIT --no-daemon`, Redis real em `localhost:6379`) passou **84/84 testes, 0 falhas, 0 skipped**: os 76 de T38–T42 mais 8 novos (`DeadLetterWriterUnitTest` 4, `DlqDurabilityIT` 4), acima dos ≥7 pedidos. `AsyncDlqIT` pré-existente (T38) continua passando sem alteração. Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| RED-07: sem off-by-one, abaixo do limite ainda permite retry | `DeadLetterWriterUnitTest.java:27-28` — `assertFalse(writer.exceedsMaxDeliveries(4), ...)` com limite 5 | 4 entregas com limite 5 ainda reclama | ✅ |
| RED-07: exatamente no limite não permite mais tentativas | `DeadLetterWriterUnitTest.java:34-35` — `assertTrue(writer.exceedsMaxDeliveries(5), ...)` com limite 5 | a 5ª entrega já ocorrida bloqueia uma 6ª | ✅ |
| RED-07: acima do limite continua bloqueado | `DeadLetterWriterUnitTest.java:41` — `assertTrue(writer.exceedsMaxDeliveries(6))` com limite 5 | nunca reabre uma janela acima do limite | ✅ |
| RED-07: limite de 1 permite exatamente 1 tentativa (fronteira mais apertada) | `DeadLetterWriterUnitTest.java:48-49` — `assertTrue(writer.exceedsMaxDeliveries(1), ...)` com limite 1 | `max-deliveries=1` não permite uma 2ª tentativa | ✅ |
| RED-07: exatamente `maxDeliveries` tentativas ocorrem de fato (evidência real do PEL) | `DlqDurabilityIT.java:83-85` — `assertEquals(3, maxObserved[0], ...)` rastreando `getRedeliveryCount()` real via `XPENDING` até o item chegar na DLQ, com `max-deliveries=3` | delivery count real do Redis nunca passa de 3 antes do DLQ | ✅ |
| RED-07: mensagem inválida (sem jobId) vai para DLQ com motivo, nunca ACK silencioso | `DlqDurabilityIT.java:96-98,102` — `assertTrue(dlqEntryWithReason(raw, "reference", ..., "missing-job-id"), ...)` e `assertTrue(raw.sync().xlen(DLQ) > dlqLenBefore, ...)` | entrada na DLQ com `dlqReason=="missing-job-id"`, DLQ cresce (não perde o item) | ✅ |
| RED-07: malformed preserva motivo (amount inválido) | `DlqDurabilityIT.java:114-116` — `assertTrue(dlqEntryWithReason(raw, "jobId", jobId, "invalid-amount"), ...)` | entrada na DLQ com `dlqReason=="invalid-amount"` e o `jobId` original preservado | ✅ |
| RED-07: ACK somente após confirmação da DLQ (negativo: sem confirmação, sem ACK) | `DlqDurabilityIT.java:135-138` — `assertEquals(1, stillPending.size(), ...)` via `XPENDING` real após a escrita na DLQ falhar (chave com tipo errado) | a mensagem permanece pendente enquanto a DLQ não confirma a escrita | ✅ |
| RED-07: DLQ failure deixa item recuperável (a falha se recupera) | `DlqDurabilityIT.java:145-148` — `assertTrue(dlqEntryHasAnyReason(raw, "reference", ...), ...)` após desbloquear a DLQ e a entrada sair do PEL | o mesmo item, antes bloqueado, chega à DLQ com um motivo assim que a DLQ volta a funcionar | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `DeadLetterWriterUnitTest.java:27-28,34-35,41,48-49` | RED-07 fronteira exata do off-by-one (4 casos) | ✅ |
| `DlqDurabilityIT.java:83-85` | RED-07 contagem real de tentativas via PEL | ✅ |
| `DlqDurabilityIT.java:96-98,102` | RED-07 malformed (jobId ausente) com motivo, sem ACK silencioso | ✅ |
| `DlqDurabilityIT.java:114-116` | RED-07 malformed (amount inválido) com motivo | ✅ |
| `DlqDurabilityIT.java:135-138,145-148` | RED-07 ACK só após DLQ confirmada + recuperação após a falha | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção de payload lê estado real — `XPENDING` para contagem de entregas, `XRANGE`/`XLEN` da DLQ para motivo e crescimento — nunca apenas que um método foi chamado. `aLimitOfOneAllowsExactlyOneAttempt` é o teste de controle que mata especificamente a implementação antiga: com `max-deliveries=1` e o bug `>`, a primeira entrega (`count==1`) passava (`1 > 1` é falso) e uma segunda tentativa ocorria; o teste corrigido exige `true` nesse ponto exato. `exactlyMaxDeliveriesAttemptsOccurBeforePoisonReachesDlq` prova a mesma fronteira fim a fim contra o Redis real e o `JobWorker` de verdade, não só o predicado isolado — matando uma implementação que corrigisse `exceedsMaxDeliveries` mas esquecesse de usá-lo no `reclaim`. `aDlqWriteFailureLeavesTheOriginalMessageRecoverableInThePel` usa uma falha real do Redis (chave de tipo errado, `WRONGTYPE`) em vez de um mock, porque a garantia é sobre o comportamento do servidor sob falha, não sobre uma chamada interceptada; a asserção final aceita qualquer motivo não vazio (não um literal fixo) porque, dependendo de quantas redeliveries se acumulam durante o bloqueio, o item pode legitimamente sair pelo caminho `missing-job-id` ou pelo `max-deliveries-exceeded` — ambos preservam motivo e são recuperáveis, que é exatamente o que a AC exige; fixar um dos dois teria sido uma asserção artificialmente frágil e teria testado o timing, não o requisito. Nenhum teste verifica comportamento do driver Lettuce isoladamente. Liberação atômica de resultado é do T42 e não foi tocada aqui; retenção PEL-safe segue com T44. Sem SPEC_DEVIATION: os limites de validação de malformed (jobId e amountCents) foram escolhidos por serem os dois campos que a AC de RED-07 e o cálculo de `JobResult` de fato dependem; `reference`/`note` continuam opcionais como já eram.

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
**Status:** Complete

**Gate evidence:** `JobQueue.enqueue` gravava `XADD ... MAXLEN ~` em toda publicação — trim aproximado por contagem bruta, sem qualquer noção de PEL/consumer group, exatamente o que RED-03 proíbe: sob pressão de backlog ele remove entradas que nenhum worker consumiu ainda. Removido sem substituto inline; `JobQueue.java:50-68` (novo comentário) documenta por quê.

Antes de decidir a política de substituição, verifiquei a política `ACKED` citada em `design.md` pela cadeia de conhecimento do skill (não assumida): busca web confirmou que `ACKED` (que só remove entradas confirmadas por todos os consumer groups) e `DELREF` exigem **Redis 8.2+** ([XTRIM docs](https://redis.io/docs/latest/commands/xtrim/)), e que a combinação `MAXLEN ~` (aproximado) com `ACKED` tem um bug reportado onde nada é removido ([redis/redis#14656](https://github.com/redis/redis/issues/14656)) — só o trim exato funciona corretamente com `ACKED`. Inspecionando o jar do Lettuce 6.4.0.RELEASE (`javap io/lettuce/core/XTrimArgs.class`) confirmei que `XTrimArgs` não tem nenhuma opção `ACKED` tipada nesta versão do driver — não há como emitir esse comando com suporte tipado hoje. O Redis do sandbox é 7.0.15, abaixo do mínimo de qualquer forma.

**SPEC_DEVIATION:** o `What` desta tarefa pede "exigir trim ACKED em Redis suportado". A implementação **detecta e reporta** compatibilidade (`StreamRetentionMonitor.ackedTrimSupported`) mas **nunca invoca** trim `ACKED` de fato, em nenhuma versão. **Reason:** (1) o driver fixado (Lettuce 6.4.0.RELEASE) não tem suporte tipado ao `ACKED`, só permitiria comando raw não verificável; (2) não há Redis 8.2+ disponível neste ambiente para testar via integração — implementar um caminho que o gate não consegue exercitar violaria tanto "sem inventar segurança de trim" quanto "Tests: integration"; (3) `design.md` (seção de riscos, `async-redis-service/.../JobQueue.java:1 | XADD MAXLEN ~ pode remover payload no PEL`) já registra o fallback aceito: "fallback seguro é não trimar automaticamente, nunca perder pendentes" — a implementação escolhida é exatamente esse fallback já decidido, tornado permanente e não apenas temporário, com o gate de compatibilidade como sinal operacional para uma futura atualização do driver, documentada no ADR de T45.

Full gate (`async-redis-service/./gradlew test -PwithIT --no-daemon`, Redis real 7.0.15 em `localhost:6379`) passou **96/96 testes, 0 falhas, 0 skipped**: os 84 de T38–T43 mais 12 novos (`StreamRetentionMonitorUnitTest` 6, `StreamRetentionIT` 6), acima dos ≥6 pedidos (todos os 6 exigidos são de integração contra Redis real, como pedido). Nenhum teste anterior foi removido, pulado ou enfraquecido.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| RED-03: payload pendente sobrevive à pressão de retenção (via enqueue) | `StreamRetentionIT.java:69-70` — `assertEquals(6, conn.sync().xlen(stream), ...)` com `stream-maxlen=3` e 6 entradas nunca consumidas | nenhuma das 6 entradas pendentes é removida mesmo 2x acima do maxlen | ✅ |
| RED-03: `check()` também nunca remove nada do stream | `StreamRetentionIT.java:88-89` — `assertEquals(5, status.streamLength(), ...)` e `assertEquals(5, conn.sync().xlen(stream), ...)` | o próprio monitor de retenção não trima o backlog | ✅ |
| RED-03: backlog alerta antes/no orçamento seguro | `StreamRetentionIT.java:104-105` — `assertEquals(5, status.alertThreshold())`, `assertTrue(status.backlogAlert(), ...)` com limite 0.5×10 | alerta liga exatamente ao atingir o orçamento configurado | ✅ |
| RED-03: sem alerta abaixo do orçamento (controle) | `StreamRetentionIT.java:121-122` — `assertFalse(status.backlogAlert(), ...)` com 4 de um limite de 5 | alerta não dispara cedo demais | ✅ |
| RED-03: versão do Redis é lida do servidor real, não simulada | `StreamRetentionIT.java:133-136` — `assertEquals(expected, status.serverVersion())` comparado com `INFO server` lido por uma conexão independente | a versão reportada é exatamente a do servidor conectado | ✅ |
| RED-03: versão incompatível nunca é reportada como capaz de ACKED (falha segura) | `StreamRetentionIT.java:151-152` — `assertFalse(status.ackedTrimSupported(), ...)` contra o Redis 7.0.15 real do sandbox | um servidor abaixo de 8.2.0 nunca habilita a política que ainda não pode usar | ✅ |
| RED-03: gate de versão sem off-by-one (fronteira exata) | `StreamRetentionMonitorUnitTest.java:22` — `assertEquals(0, StreamRetentionMonitor.compareVersions("8.2.0", "8.2.0"))` | a própria versão mínima já conta como compatível | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `StreamRetentionIT.java:69-70` | RED-03 payload pendente sobrevive (enqueue) | ✅ |
| `StreamRetentionIT.java:88-89` | RED-03 `check()` nunca trima | ✅ |
| `StreamRetentionIT.java:104-105,121-122` | RED-03 alerta liga no orçamento, não antes | ✅ |
| `StreamRetentionIT.java:133-136,151-152` | RED-03 versão real + gate de incompatibilidade | ✅ |
| `StreamRetentionMonitorUnitTest.java:16-46` (6 testes) | RED-03 fronteira do compare de versão que sustenta o gate acima | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção lê estado real do Redis — `XLEN` para backlog/payload, `INFO server` para versão — nunca um mock. `enqueueNeverTrimsSoAllPendingPayloadSurvivesPastMaxlen` é o teste de controle direto do bug original: com o código antigo (`MAXLEN ~` inline), esse teste teria falhado (menos de 6 entradas restando). `checkAlertsOnceTheBacklogReachesTheSafeThreshold`/`checkDoesNotAlertBelowTheSafeThreshold` são um par de fronteira (5 de 5 vs. 4 de 5) porque um teste sozinho não distingue ">=" de ">" no gate de alerta. `theMinimumVersionItselfCompliesExactly` mata especificamente uma implementação que usasse `>` em vez de `>=` no compare de versão (a mesma classe de bug corrigida em T43, aqui em outro predicado). `checkReportsTheRealConnectedRedisServerVersion` compara contra uma leitura independente do `INFO server`, não contra um valor fixo esperado, então continua válido em qualquer versão de Redis que rode o gate. Nenhum teste verifica comportamento do driver Lettuce isoladamente. Liberação atômica é do T42; poison/DLQ é do T43 — nenhum foi tocado aqui. O trim `ACKED` em si (quando um Redis 8.2+ e um Lettuce compatível existirem) fica registrado como trabalho futuro no ADR de T45, não implementado aqui — ver SPEC_DEVIATION acima.

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
**Status:** Complete

**Gate evidence:** Antes desta tarefa a raiz tinha `settings.gradle`/build/wrapper (T38) mas nenhum artefato produtivo próprio — sem Dockerfile, Compose, `.env.example`, README, `AGENTS.md`, docs, ADR, CI ou runbooks (ORG-03). Adicionados, seguindo o padrão já aprovado em `payment-api`/`payment-sbus`, adaptado para esta fronteira ser standalone (sem `payment-contracts`/`feature-control` como contexto de build — `StandaloneBoundaryTest` já garante isso): `Dockerfile` multi-stage (`gradle:8.14.3-jdk21-alpine` -> `eclipse-temurin:21-jre-alpine`, ambos fixados por tag+digest, `installDist`, `USER 10001:10001`, `HEALTHCHECK` via `wget` sem pacote extra — SEC-07), `.dockerignore`, `compose.yaml` (rede externa do sandbox, `read_only`, `tmpfs` para `/tmp`, `cap_drop: ALL`, `no-new-privileges` — SEC-07), `.env.example` sem segredo, `README.md`, `AGENTS.md`, `docs/{architecture,contracts,configuration,security,operations,observability,testing,performance}.md` (DOC-01/02/03), `docs/adr/0001-stream-retention-and-wakeup-protocol.md` registrando as decisões de T42/T44 (DOC-04), `ops/runbooks/*` e `ops/alerts/async-redis-alerts.yml`, `load/k6-smoke.js`, `scripts/{verify-docs.sh,validate_docs.py,test_docs.py}`, `deploy/{verify.sh,test_release_package.py}` e `.github/workflows/ci.yml` próprio (build, IT com serviço Redis, docs, `docker buildx build`, SBOM SPDX via `anchore/sbom-action`, scan via `aquasecurity/trivy-action` bloqueando HIGH/CRITICAL — SEC-08). SEC-01 não exigiu código novo: `ProductionAcceptanceGuard` (T39) já recusa produção sem os três gates de RED-08; esta tarefa apenas documenta esse contrato em `docs/security.md` e `docs/configuration.md`.

Corrigido de passagem: o comentário de `stream-maxlen` em `application.yml` ainda dizia "Approximate cap ... (XADD MAXLEN ~)" depois que T44 removeu esse trim — comentário desatualizado e enganoso para quem lê a config produtiva. Corrigido para descrever o uso atual (orçamento de alerta do `StreamRetentionMonitor`) e adicionado `retention-alert-threshold` explícito ao YAML.

Full gate: `./gradlew build --no-daemon` (compila, empacota, `check`) e `./gradlew test -PwithIT --no-daemon` (96/96, inalterado) passam. `scripts/verify-docs.sh` (3 testes) e `deploy/verify.sh --structural` (9 testes, dentro do mesmo `unittest discover`) passam — **12/12 testes estruturais, 0 falhas**. `docker compose --env-file .env.example -f compose.yaml config -q` valida. `git diff --check` limpo.

**Bloqueio de ambiente, documentado (não um defeito de código):** `docker buildx build` para a imagem real falha porque este ambiente sandbox bloqueia egress para o Docker Hub — confirmado com `docker pull hello-world:latest` (`403 Forbidden` do CDN) e reproduzido no próprio build (`failed to resolve source metadata for docker.io/docker/dockerfile:1.7: ... Forbidden`), mesma política de rede já observada e documentada pelo orquestrador para a infra do sandbox. Isso bloqueia especificamente o `deploy/verify.sh` sem `--structural` (que builda e inspeciona o container) e o passo `docker buildx build` do CI local — ambos corretos e vão funcionar normalmente em CI real com acesso ao registry (é assim que `payment-api`/`payment-sbus` já operam). Como evidência substituta alcançável para "load smoke passam": `./gradlew installDist` gerou a distribuição real, executada diretamente (não containerizada) contra o Redis real do sandbox (`localhost:6379`); `/health/liveness` e `/health/readiness` responderam `200`/`UP` com `consumingWorkers: 2`; `k6 run load/k6-smoke.js` (5 req/s, 15s) fechou **152/152 checks, 0 falhas, 0 requisições HTTP falhas**, com `feeCents` correto (2% de `amountCents`) em toda resposta `200` e `202`+polling. Isso prova o comportamento fim a fim do artefato empacotado (`installDist`, o mesmo passo que o `Dockerfile` usa) sob carga real; só a etapa de containerização em si ficou `NOT_RUN` neste ambiente.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| ORG-03: artefatos próprios completos | `deploy/test_release_package.py:14-15,19-23` — `assertEqual(2, len(images))`, `assertTrue(all(":" in ... "@sha256:" in ...))`, `assertIn("USER 10001:10001", ...)`, `assertIn("./gradlew installDist", ...)` | Dockerfile próprio, bases fixadas, runtime não-root | ✅ |
| ORG-03: standalone, sem contexto cross-boundary | `deploy/test_release_package.py:29-34` — `assertNotIn("--from=contracts-repository", ...)`, `assertNotIn("additional_contexts", COMPOSE)` | build não depende de outra fronteira do workspace | ✅ |
| SEC-07: container não-root, filesystem/capabilities restritos | `deploy/test_release_package.py:44-48` — `assertIn("read_only: true", ...)`, `assertRegex(COMPOSE, r"cap_drop:\s+\- ALL")`, `assertIn('user: "10001:10001"', ...)` | Compose aplica os quatro controles exigidos | ✅ |
| SEC-07: healthcheck sem pacote extra | `deploy/test_release_package.py:25-27` — `assertIn("HEALTHCHECK", ...)`, `assertNotRegex(DOCKERFILE, r"\b(?:apk\|apt-get)\b")` | nenhum `apk`/`apt-get` adicionado só para o healthcheck | ✅ |
| SEC-08: CI gera SBOM e bloqueia HIGH/CRITICAL | `deploy/test_release_package.py:50-55` — `assertIn("sbom-action", WORKFLOW)`, `assertIn("trivy-action", WORKFLOW)`, `assertIn("exit-code: '1'", ...)`, `assertIn("severity: 'HIGH,CRITICAL'", ...)` | pipeline gera inventário e bloqueia a severidade aprovada | ✅ |
| DOC-01: README cobre propósito/quickstart/deps/operação/status | `scripts/test_docs.py:18-19` (`validate` sobre `REQUIRED_MARKERS["README.md"]`) — `assertEqual([], async_redis_docs.validate(ROOT))` com os 5 marcadores obrigatórios presentes | as 5 seções exigidas existem no README real | ✅ |
| DOC-02: AGENTS.md cobre mapa/fontes/invariantes/limites/gates | `scripts/test_docs.py:18-19` sobre `REQUIRED_MARKERS["AGENTS.md"]` | as 5 seções exigidas existem no AGENTS.md real | ✅ |
| DOC-03: docs proporcionais (arquitetura a performance) presentes | `scripts/test_docs.py:21-24` — `assertEqual(len(REQUIRED), len(errors))` num diretório vazio, prova que os 18 documentos são de fato exigidos | ausência de qualquer doc obrigatório é detectada | ✅ |
| DOC-04: ADR numerado com as 5 seções e status Accepted | `validate_docs.py` (`ADR_HEADINGS` + `"Status: Accepted"`), exercido por `scripts/test_docs.py:18-19` contra o ADR real | ADR-0001 tem Contexto/Decisão/Alternativas/Consequências/Supersession e está Accepted | ✅ |
| Links e claims da documentação não quebram | `scripts/test_docs.py:26-32` — `assertTrue(any("broken link" in error ...))` num link fabricado | um link quebrado é detectado, não silenciosamente aceito | ✅ |
| Done-when: imagem/Compose/CI/docs passam (estrutural) | `deploy/verify.sh` execução real nesta sessão — saída `async-redis-release-package: PASS (structural)` após os 9 testes de `deploy/test_release_package.py` | gate estrutural completo passa | ✅ |
| Done-when: load smoke passa (evidência substituta, ver bloqueio acima) | execução real de `k6 run load/k6-smoke.js` nesta sessão — `checks_succeeded: 100.00% (152/152)`, `http_req_failed: 0.00%` | o artefato empacotado (`installDist`) processa carga real fim a fim sem falha | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `deploy/test_release_package.py:14-15,19-23,29-34` | ORG-03 artefatos próprios e standalone | ✅ |
| `deploy/test_release_package.py:25-27,44-48` | SEC-07 container/runtime | ✅ |
| `deploy/test_release_package.py:50-55` | SEC-08 SBOM e scan bloqueante | ✅ |
| `deploy/test_release_package.py:57-59,61-65` | ORG-03 `.env.example` sem segredo e runbooks completos | ✅ |
| `scripts/test_docs.py:18-19,21-24,26-32` | DOC-01/02/03/04 pacote de documentação completo, consistente e com ADR válido | ✅ |

**Adequacy review:** cobertura suficiente e necessária para o que é estrutural/gate-verificável neste ambiente. `test_missing_package_is_rejected` e `test_broken_link_is_rejected` são os controles que impedem `validate_docs.py` de ser um verificador de fachada: um diretório vazio precisa reprovar em todos os 18 documentos, e um link fabricado precisa ser pego, não silenciosamente ignorado. `test_dockerfile_declares_no_cross_boundary_build_context` mata especificamente uma regressão que reintroduzisse acoplamento com `payment-contracts`/`feature-control`, o que violaria o próprio `StandaloneBoundaryTest` do build principal. Nenhum teste aqui afirma que a imagem Docker builda de fato nem que o container roda — isso é honestamente reportado como `NOT_RUN`/bloqueio de ambiente, não fabricado como passagem; a AC de "load smoke" é atendida por uma execução real do artefato instalado (`installDist`) contra Redis real e k6 real, não uma simulação. SEC-01 não gerou teste novo porque não gerou código novo — a cobertura de `ProductionAcceptanceGuardUnitTest` (T39) já existe e permanece intacta. Nenhum teste anterior foi removido, pulado ou enfraquecido. SPEC_DEVIATION: nenhuma nova nesta tarefa; a de T44 (trim `ACKED` não invocado) permanece documentada no ADR-0001 criado aqui, como a própria tarefa pedia.

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
**Status:** Complete

**Gate evidence:** `feature-control/` held the library flat (`build.gradle` + `src/`); `feature-demo/` and `pilot-app/` were separate top-level modules included only by the transitional workspace root. Following the same pattern as T7 (`payment-contracts`), moved (via `git mv`) `feature-control/{build.gradle,src}` into `feature-control/library/`, and `feature-demo/`/`pilot-app/` into `feature-control/examples/{feature-demo,pilot-app}/`. Gave `feature-control/` its own `settings.gradle`, `build.gradle`, `gradle.properties` and Gradle wrapper (copied from `async-redis-service`, same Gradle 8.14.3). The library subproject keeps the Gradle path `:feature-control` (matching its published `artifactId`) inside the standalone `settings.gradle` via `project(':feature-control').projectDir = file('library')`, so `examples/{feature-demo,pilot-app}/build.gradle`'s existing `implementation project(':feature-control')` line needed zero edits to keep resolving to the library in both topologies.

**Root-include preservation (MIG-02, explicit deviation from a literal in-place edit):** the task's `Reuses` field and MIG-02 require the outer aggregator's `include` to stay meaningful, not just textually present. Editing `feature-control/build.gradle` into a multi-module aggregator would have broken the transitional root's `:feature-control`/`:feature-demo`/`:pilot-app` single-module expectation. Instead, the workspace root `settings.gradle:1` keeps all three `include` lines and adds `project(':feature-control').projectDir = file('feature-control/library')` (and the equivalent for the two examples) — same directory-repoint mechanism already used by T38 for `async-redis-service`, extended to a multi-project move. Verified both topologies build the exact same sources: `./gradlew :feature-control:test :feature-demo:test :pilot-app:test -PwithIT --no-daemon` from the repo root passes 35/35 (20 unit + 4 new structural + 9 `FeatureDemoFlowIT` + 2 `PilotIT`), and `feature-control/gradlew test -PwithIT --no-daemon` from the new standalone root passes the same 35/35 — the two builds compile and test the identical files on disk, only the settings topology differs.

Also fixed the root `Dockerfile:36,46` `COPY --from=build` paths for the `feature-demo`/`pilot` targets, since the Gradle build output directory moved with the source (`docker compose config -q` was not re-verified against a real image build in this sandbox — Docker Hub egress is blocked here per T44/T45's prior notes — but the `COPY` source paths now match the real post-move Gradle output paths, checked by reading `feature-control/examples/*/build/libs/*-runner.jar` after the Gradle run above).

Added `library/build.gradle`, `examples/feature-demo/build.gradle` and `examples/pilot-app/build.gradle`: `java.toolchain` + `repositories { mavenCentral() }`, previously supplied only by the transitional root's `subprojects{}` block and now needed for the standalone build to resolve/compile on its own (ORG-02: a boundary must build from its own root without reading another root's `build.gradle`).

Added `feature-control/library/src/test/java/.../StandaloneBoundaryTest.java` (4 new structural tests, mirroring T38's `async-redis-service` guard): own build files/wrapper present; the library's `build.gradle` declares `maven-publish`, no `project(...)` dependency and no `com.example.payments`/`com.example.platform.asyncredis` reference; both examples depend only on `project(':feature-control')` (the boundary-local library) and declare no `maven-publish` (not independent releases, per this task's own Done-when); no Java source under `library/src` imports another boundary's package.

Baseline (31 methods: `feature-control` 20, `feature-demo` 9, `pilot-app` 2) fully preserved — none removed, skipped or weakened; +4 new structural tests = 35/35 total, both topologies, 0 failures, 0 skipped.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| ORG-02: a fronteira compila/testa a partir da própria raiz sem ler settings/build/wrapper de outra raiz | `feature-control/library/src/test/.../StandaloneBoundaryTest.java:28-35` — `assertTrue(Files.isRegularFile(BOUNDARY_ROOT.resolve("settings.gradle")))` etc., mais execução real de `feature-control/gradlew test -PwithIT --no-daemon` (35/35 passou) | a raiz standalone possui build/wrapper próprios e builda sozinha | ✅ |
| ORG-04: a biblioteca publicada possui build/wrapper/publicação Maven próprios | `StandaloneBoundaryTest.java:38-45` — `assertTrue(build.contains("maven-publish"))` sobre `library/build.gradle` | `library` declara o plugin `maven-publish` | ✅ |
| ORG-05: nenhum `project(':...')` entre fronteiras | `StandaloneBoundaryTest.java:41` — `assertFalse(build.contains("project("))` sobre `library/build.gradle`; `StandaloneBoundaryTest.java:50-58` — `assertTrue(build.contains("project(':feature-control')"))` só permite a dependência local (design.md 2.2 explicitamente permite exemplo -> library dentro da mesma fronteira) | biblioteca sem dependência de projeto; exemplos só dependem do projeto local | ✅ |
| MIG-02: localização antiga permanece provável até equivalência ser demonstrada | evidência de execução (não citação de arquivo): `./gradlew :feature-control:test :feature-demo:test :pilot-app:test -PwithIT --no-daemon` (raiz do workspace) = 35/35; `feature-control/gradlew test -PwithIT --no-daemon` (raiz standalone) = 35/35, mesmos arquivos-fonte | as duas topologias compilam e testam os mesmos arquivos com o mesmo resultado | ✅ |
| Done-when: ≥31 testes preservados | contagem de saída do Gradle nas duas execuções acima: 20 (`feature-control` unit, incluindo os 4 novos estruturais) + 9 (`FeatureDemoFlowIT`) + 2 (`PilotIT`) = 35 ≥ 31 | nenhum teste anterior removido/enfraquecido | ✅ |
| Done-when: exemplos não são releases independentes | `StandaloneBoundaryTest.java:52-53` — `assertFalse(build.contains("maven-publish"))` para cada exemplo | nenhum exemplo declara publicação Maven própria | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `StandaloneBoundaryTest.java:28-35` | ORG-02 raiz standalone própria | ✅ |
| `StandaloneBoundaryTest.java:39-45` | ORG-04 publicação Maven própria | ✅ |
| `StandaloneBoundaryTest.java:41,50-51` | ORG-05 sem `project()` cross-boundary; exemplo -> library permitido | ✅ |
| `StandaloneBoundaryTest.java:52-53` | Done-when exemplos não publicam | ✅ |
| `StandaloneBoundaryTest.java:63-72` | ORG-07-adjacente: nenhuma fonte importa outra fronteira | ✅ |
| 35/35 verde nas duas topologias (Gradle output, não um `file:line`) | MIG-02 equivalência funcional | ✅ |

**Adequacy review:** cobertura suficiente e necessária para o que este task pede (estrutural + preservação de baseline). As quatro novas asserções estruturais são controles diretos contra a própria regressão que este move poderia introduzir: `libraryPublishesAndDeclaresNoCrossBoundaryDependency` mata uma reintrodução de `project(':...')` na library; `examplesDependOnlyOnTheLocalLibraryProjectAndPublishNothing` mata tanto um exemplo apontando para outra fronteira quanto um exemplo ganhando `maven-publish` por engano; `librarySourcesImportNothingFromAnotherBoundary` reusa o padrão já validado em T38. Nenhuma asserção nova testa comportamento de negócio da lib (isso é T47-T51); o teste de equivalência funcional (31 métodos preservados, dois topologias verdes) é a evidência de execução real do Gradle, não fabricada. Nenhum teste anterior foi removido, pulado ou enfraquecido. SPEC_DEVIATION: nenhuma no comportamento; a única nota é a técnica de dupla topologia (`projectDir` repoint em vez de editar `feature-control/build.gradle` em lugar), documentada acima como a forma escolhida de honrar "keep the root include" sem quebrar o aggregator transicional — decisão de engenharia, não desvio do requisito.

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
**Status:** Complete

**Gate evidence:** `FlagDefinition`'s compact constructor (the single construction choke point used by YAML binding, the admin write path and Redis deserialization alike) only checked non-blank name, `percentage` in `[0,100]` and `version >= 0`. Nothing bounded name length/charset, variant weights/names/count, allowlist membership, or label/salt length, and nothing rejected `variants` on a non-VARIANT flag — exactly the "combinações inválidas" FTR-01 requires catching before persist/activate.

Extended the compact constructor with: a name length bound (100) and charset (`[A-Za-z0-9_][A-Za-z0-9_.-]*`, chosen to keep the reserved `__kill_switch__` name and every existing YAML flag name valid, while rejecting whitespace and `:` — the latter would collide with the Redis `key-prefix` separator); label length bounds (100) on `onVariant`/`offVariant`/each variant name and on `bucketingSalt` (200); for `VARIANT` flags, a non-empty variant list, no duplicate variant names, and at least one variant with weight > 0 (an all-zero-weight VARIANT can never differentiate — `Bucketer.select` already special-cased this by always returning the first variant, which is exactly the silent-misconfiguration case FTR-01 asks to catch instead); `variants` rejected on any non-VARIANT flag; and, for an *enabled* `ALLOWLIST` flag, at least one allowed user or group (a disabled placeholder is still accepted, since `FeatureResolver` never reaches the allowlist branch for a disabled flag).

Quick gate (`feature-control/gradlew test --no-daemon`) passed **54/54 unit tests, 0 failures, 0 skipped**: the 24 pre-existing (20 baseline + 4 T46 structural) plus 30 new in `FlagDefinitionValidationUnitTest`, above the ≥15 requested. Re-ran the full gate (`-PwithIT`, real Redis) to confirm the stricter validation does not reject any existing baseline flag definition: `FeatureDemoFlowIT` (9) and `PilotIT` (2) both still pass unchanged — every flag name/combination in `feature-demo`/`pilot-app`'s `application.yml` was already a valid combination under the new rules. No test removed, skipped or weakened.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| FTR-01: nomes têm bounds (tamanho e charset) | `FlagDefinitionValidationUnitTest.java:44-58` — `assertThrows(IllegalArgumentException.class, ...)` para nome vazio, nulo, >100 chars, com espaço e com `:` | construção rejeita determinísticamente cada nome inválido | ✅ |
| FTR-01: nome válido no limite e caso reservado continuam construíveis | `FlagDefinitionValidationUnitTest.java:60-70` — `assertDoesNotThrow(...)` para nome de 1 char, `__kill_switch__` e nome com 100 chars | nenhuma regressão no `MasterSwitch.KILL_FLAG` nem em nomes de produção existentes | ✅ |
| FTR-01: percentuais têm bounds | `FlagDefinitionValidationUnitTest.java:75-96` — `assertThrows` para -1 e 101, `assertDoesNotThrow` para 0 e 100 | `[0,100]` é a fronteira exata, testada nos dois lados | ✅ |
| FTR-01: versões têm bounds | `FlagDefinitionValidationUnitTest.java:101-111` — `assertThrows` para versão -1, `assertDoesNotThrow` para 0 | versão negativa nunca persiste | ✅ |
| FTR-01: pesos/variantes têm combinação válida | `FlagDefinitionValidationUnitTest.java:116-155` — `assertThrows` para lista vazia, nomes duplicados, todos os pesos zero e variantes fora de tipo VARIANT; `assertDoesNotThrow` para ao menos um peso positivo | toda combinação inválida de VARIANT é rejeitada antes de persistir | ✅ |
| FTR-01: combinações de ALLOWLIST são válidas | `FlagDefinitionValidationUnitTest.java:160-183` — `assertThrows` para ALLOWLIST habilitada sem usuários/grupos; `assertDoesNotThrow` para desabilitada sem membros, e habilitada só com grupo ou só com usuário | um ALLOWLIST habilitado sem alvo nunca é aceito; um placeholder desabilitado continua permitido | ✅ |
| FTR-01: salts e labels têm bounds | `FlagDefinitionValidationUnitTest.java:188-215` — `assertThrows`/`assertDoesNotThrow` na fronteira exata de `bucketingSalt` (200) e `onVariant`/`offVariant` (100) | nenhum campo de texto livre é ilimitado | ✅ |
| Done-when: combinações válidas/inválidas têm resultado determinístico | todas as 30 asserções acima, mais `FeatureDemoFlowIT`/`PilotIT` inalterados sob a validação mais estrita | mesma entrada sempre produz o mesmo resultado (aceita ou rejeita) | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `FlagDefinitionValidationUnitTest.java:44-70` | FTR-01 bounds/charset de nome | ✅ |
| `FlagDefinitionValidationUnitTest.java:75-96` | FTR-01 bounds de percentual | ✅ |
| `FlagDefinitionValidationUnitTest.java:101-111` | FTR-01 bounds de versão | ✅ |
| `FlagDefinitionValidationUnitTest.java:116-155` | FTR-01 combinação de pesos/variantes | ✅ |
| `FlagDefinitionValidationUnitTest.java:160-183` | FTR-01 combinação de ALLOWLIST | ✅ |
| `FlagDefinitionValidationUnitTest.java:188-215` | FTR-01 bounds de salt/labels | ✅ |
| `FlagDefinitionValidationUnitTest.java:219-224` | comportamento pré-existente (type null -> BOOLEAN) preservado, não uma nova AC | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Cada teste de rejeição tem seu par de aceitação na fronteira exata (percentual 0/100, salt/label no limite exato, versão 0), o que mata tanto uma implementação frouxa (`>=` vs `>`) quanto uma excessivamente restritiva. `rejectsVariantFlagWithAllZeroWeights` é o teste de controle mais específico: sem ele, uma implementação que só verificasse "lista não vazia" passaria por um VARIANT inútil (todos os pesos zero) — o teste força a leitura da soma dos pesos, não só do tamanho da lista. `acceptsDisabledAllowlistWithNoUsersOrGroups` existe para não quebrar o padrão de placeholder já implícito no `FeatureResolver` (que checa `enabled()` antes do branch ALLOWLIST); sem esse teste, a regra ficaria mais restritiva do que o próprio resolver exige. Nenhum teste usa apenas contagem de chamada; todos constroem o objeto real e verificam a exceção (ou a ausência dela) e, no caso de `nullTypeDefaultsToBoolean`, o valor do campo resultante. Nenhum teste verifica biblioteca/framework isoladamente. Reverse-mapping (Check C): toda asserção nova mapeia diretamente para uma dimensão nomeada em FTR-01 (nomes, pesos, versões, limites, combinações); nenhuma é especulativa. Nenhum teste anterior foi removido, pulado ou enfraquecido; a suíte de exemplos (`FeatureDemoFlowIT`/`PilotIT`) foi reexecutada como controle negativo de regressão e permanece verde. Sem SPEC_DEVIATION: a única decisão de engenharia não ditada literalmente pela spec foi tratar "todos os pesos zero" e "ALLOWLIST habilitado sem alvo" como combinações inválidas — ambas documentadas acima como leitura razoável de "combinações inválidas" per FTR-01, e nenhuma quebra o baseline.

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
**Status:** Complete

**Gate evidence:** `RedisFlagSource.find` had three FTR-02 gaps, all confirmed by reading the code before changing it. (1) **Unbounded stale:** on a Redis failure it always served the cached value "if we have one" regardless of age — a value fetched days ago would keep being served forever, with no `maxStale` bound and no fallback once exceeded. (2) **No observable age:** nothing exposed how old the currently-served value was. (3) **No stampede protection:** every concurrent caller that missed the cache independently issued its own Redis `GET`; on a hot flag whose `cache-ttl` just expired, N concurrent requests meant N redundant round trips, and no jitter meant many keys populated at the same instant (e.g. at startup) would all expire in lockstep later.

Added `source/StaleFallback` (`BASELINE`/`FAIL_CLOSED`), `source/StalePolicy` (pure decision function: last-known-good while `age <= maxStale`, else defer to baseline or force the flag off) and `source/CacheJitter` (pure, seedable-`Random` jitter on the per-key TTL). Extended `FeatureSettings` with `max-stale` (default 5m), `stale-fallback` (default `BASELINE`) and `cache-ttl-jitter` (default 0.1). Narrowed `RedisFlagSource`'s Redis dependency from the full `FeatureRedisCommandsProvider` to a new 1-method `source/FlagKeyReader` interface (`String get(String key)` — the only Redis operation this class ever called); `FeatureRedisCommandsProvider` now also implements it, so production wiring is unchanged, but tests can inject a real-Redis-backed reader with a deterministic on/off failure switch instead of needing to fake the entire Lettuce command surface. `RedisFlagSource.find` now uses a per-key `synchronized` double-checked-lock for single-flight: on a cache miss, the first thread through the lock is the only one that calls Redis; every other concurrent caller for the same key blocks, then re-checks the (now-fresh) cache and reuses that result. A new `ageOf(name)` accessor reports how long ago a value was last successfully fetched.

`StalePolicy.apply` deliberately always forces a fail-closed value to `FlagType.BOOLEAN` regardless of the last-known-good's original type: emptying a VARIANT flag's variant list to "force it off" would trip T47's own FTR-01 validation (VARIANT requires at least one variant), and it doesn't matter anyway — `FeatureResolver` short-circuits on `enabled()==false` before it ever reaches the type-specific branch.

`CompositeFlagSource` needed **zero changes**: its existing `if (override.isPresent()) return override; else return baseline.find(name)` already implements exactly "BASELINE = defer" (return `Optional.empty()`) and "present-but-disabled = fail-closed" (return a definition), so the whole BASELINE/FAIL_CLOSED policy lives entirely in what `RedisFlagSource` returns.

Full gate (`feature-control/gradlew test -PwithIT --no-daemon`, real Redis at `localhost:6379`) passed **24 new tests** (15 pure unit — `StalePolicyUnitTest` 8, `CacheJitterUnitTest` 7 — plus 9 in `RedisFlagSourceIT` against real Redis), above the ≥10 requested, on top of the 54 pre-existing (0 failures, 0 skipped). Re-ran `feature-demo`/`pilot-app`'s ITs (11 tests) unchanged as a regression control — both still pass against the rewritten `RedisFlagSource`.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| FTR-02: LKG servido dentro de `maxStale` | `RedisFlagSourceIT.java:118-125` — `assertEquals(first.enabled(), duringOutage.enabled(), ...)` após simular outage a 80ms (cache-ttl 50ms, max-stale 200ms) | valor anterior continua sendo servido sem alteração | ✅ |
| FTR-02: fronteira exata de `maxStale` (unitário) | `StalePolicyUnitTest.java:33-37,40-44` — `assertEquals(LKG, ...)` em idade==maxStale; `assertTrue(result.isEmpty())` em idade==maxStale+1 | `<=` é a fronteira, testada dos dois lados | ✅ |
| FTR-02: política BASELINE além de `maxStale` | `RedisFlagSourceIT.java:129-137` — `assertTrue(result.isEmpty(), ...)` | fonte dinâmica devolve vazio, composite cai para o baseline YAML | ✅ |
| FTR-02: política FAIL_CLOSED além de `maxStale` | `RedisFlagSourceIT.java:141-150` — `assertFalse(afterOutage.enabled(), ...)` | flag força off independente do que o baseline diria | ✅ |
| FTR-02: nunca buscado + FAIL_CLOSED ainda força off | `RedisFlagSourceIT.java:154-160` — `assertFalse(result.enabled())` | ausência total de LKG também é tratada como stale | ✅ |
| FTR-02: idade máxima observável | `RedisFlagSourceIT.java:99-104` — `assertTrue(age.toMillis() < 2_000, ...)`; `RedisFlagSourceIT.java:108-110` — `assertTrue(source.ageOf(...).isEmpty())` | idade é uma API real, consultável, não apenas um log | ✅ |
| FTR-02: single-flight sob concorrência real | `RedisFlagSourceIT.java:172-201` — `assertEquals(callsBeforeStampede + 1, reader.callCount(), ...)` com 20 threads concorrentes contra Redis real | 20 misses simultâneos da mesma chave viram exatamente 1 chamada Redis | ✅ |
| FTR-02: jitter espalha expiração (evita stampede correlacionado) | `CacheJitterUnitTest.java:15-22,24-31,68-74` — `assertEquals(12_000, ...)`/`assertEquals(8_000, ...)` nos extremos do jitter de 20%; `assertTrue(a != b, ...)` para sementes distintas | TTL nunca sai de `[base*(1-f), base*(1+f)]`; sementes diferentes divergem | ✅ |
| Done-when: outage antes/depois de maxStale, recovery e concorrência produzem policy correta | todas as linhas acima, mais `freshFetchIsServedFromRedisAndThenFromCache` (`RedisFlagSourceIT.java:88-96`) como controle de "recovery"/caso feliz | cobertura fim a fim contra Redis real, não simulada | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `StalePolicyUnitTest.java:24-96` (8 testes) | FTR-02 política LKG/BASELINE/FAIL_CLOSED, fronteira exata e caso VARIANT | ✅ |
| `CacheJitterUnitTest.java:14-92` (7 testes) | FTR-02 bounds e espalhamento do jitter | ✅ |
| `RedisFlagSourceIT.java:88-96` | FTR-02 caminho feliz (fetch fresco + cache hit) | ✅ |
| `RedisFlagSourceIT.java:99-110` | FTR-02 idade observável | ✅ |
| `RedisFlagSourceIT.java:113-160` | FTR-02 LKG dentro/além de maxStale, BASELINE e FAIL_CLOSED, com e sem fetch prévio | ✅ |
| `RedisFlagSourceIT.java:163-201` | FTR-02 single-flight sob concorrência real | ✅ |

**Adequacy review:** cobertura suficiente e necessária. `concurrentMissesForTheSameKeyCollapseIntoASingleRedisCall` é o teste de controle mais forte: uma implementação sem lock (a antiga) teria produzido até 20 chamadas ao Redis nesse cenário; a asserção `callsBeforeStampede + 1` mata qualquer versão que não serialize o refresh por chave. `oneMillisecondBeyondMaxStaleWithBaselinePolicyDefersToBaseline`/`atExactlyMaxStaleStillServesLastKnownGood` são o par de fronteira que mata um `<` trocado por `<=` (ou vice-versa) na comparação de idade. `forcedOffDefinitionIsAlwaysBooleanEvenForAVariantFlag` mata especificamente uma implementação ingênua que tentasse reconstruir o tipo original ao forçar off, o que quebraria contra a própria validação do T47. Todas as asserções de estado leem o resultado real (`FlagDefinition.enabled()`, `ageOf(...)`, contagem real de chamadas Redis) — nenhuma verifica apenas que um método foi chamado. Reverse-mapping (Check C): toda asserção nova mapeia a uma frase literal de FTR-02 (LKG, maxStale, baseline, fail-closed, idade observável, single-flight/jitter); nenhuma é especulativa, e nenhuma testa o driver Lettuce isoladamente. Nenhum teste anterior foi removido, pulado ou enfraquecido; `FeatureDemoFlowIT`/`PilotIT` foram reexecutados como controle negativo e continuam verdes. Sem SPEC_DEVIATION: `CompositeFlagSource` não foi tocado porque já implementava a metade "presente vs. vazio" da política; a única decisão de engenharia é a escolha do lock `synchronized` por chave (em vez de um executor dedicado) para single-flight — mais simples, sem outro ciclo de vida de thread pool para gerenciar, e suficiente porque `find()` já é uma chamada síncrona/bloqueante ponta a ponta.

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
**Status:** Complete

**Gate evidence:** Read `FlagChangeSubscriber` before changing it and confirmed two FTR-03 gaps. (1) **Connection leak on a partial failure:** `trySubscribe` assigned `pubSub = redisClient.connectPubSub()` directly to the field; if `connectPubSub()` succeeded but the following `addListener`/`subscribe(channel)` call threw, that already-open connection was never closed — the next retry's `connectPubSub()` overwrote the field, orphaning the first connection with no reference left to close it. (2) **Fixed retry delay:** every retry waited a hardcoded 5 seconds, so every instance recovering from the same Redis outage would reconnect in lockstep instead of spreading load. There was also no measurement of change-propagation latency across instances at all.

Extracted `pubsub/PubSubConnector` (a 2-method interface: `connect()`/`Connection{addListener,subscribe,isOpen,close}`) narrowing `FlagChangeSubscriber`'s Redis dependency the same way T48 narrowed `RedisFlagSource`'s — `pubsub/LettucePubSubConnector` is the production adapter; a hand-written fake in tests injects a `subscribe()` failure deterministically (a scenario essentially unreachable against a real, healthy local Redis, since `SUBSCRIBE` almost never fails once connected). `FlagChangeSubscriber.trySubscribe` now builds a local `candidate` connection and only assigns it to the `connection` field after every step succeeds; any exception at any step closes `candidate` before scheduling a retry, so a partial connection is never orphaned. Added `pubsub/ReconnectBackoff` (pure, capped exponential + jitter, mirrors T48's `CacheJitter`) and wired it into the retry schedule via new `FeatureSettings` fields `pubsub-reconnect-base-delay` (200ms) / `pubsub-reconnect-max-delay` (30s).

For convergence measurement: `FlagChangeNotifier.publish` now embeds the publish timestamp in the payload (`<flagName-or-*>|<epochMillis>`); `pubsub/ChangeMessage.parse` is the pure parser (a payload without the envelope, e.g. from an older library version, still invalidates by treating the whole string as the flag name — only convergence tracking is skipped for it, never breaking invalidation). Added `pubsub/ConvergenceTracker`, which records the observed publish-to-receive latency and flags it "degraded" once it exceeds the new `convergence-alert-threshold` setting (2s default), logging a WARN alert. `FlagChangeSubscriber` exposes `lastConvergenceLatency()`/`isConvergenceDegraded()` so this is queryable, not just a log line.

Full gate (`feature-control/gradlew test -PwithIT --no-daemon`, real Redis at `localhost:6379`) passed **32 new tests** (23 pure unit — `ChangeMessageUnitTest` 5, `ReconnectBackoffUnitTest` 6, `ConvergenceTrackerUnitTest` 5, `FlagChangeSubscriberUnitTest` 7 — plus 2 in `FlagChangeSubscriberConvergenceIT` against real Redis with two independent subscriber instances), above the ≥7 requested, on top of the 78 pre-existing (0 failures, 0 skipped). Re-ran `feature-demo`/`pilot-app`'s 11 ITs unchanged, including `runtimeFlipViaAdminChangesDecision` (the one that exercises the real admin-write -> publish -> subscribe -> invalidate path end to end) — still green.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| FTR-03: restart/outage não vaza conexão | `FlagChangeSubscriberUnitTest.java:66-79` — `assertTrue(connector.connections.get(0).closed.get(), ...)` após uma falha de `subscribe()` simulada | a conexão parcial é fechada antes do retry, nunca órfã | ✅ |
| FTR-03: reconecta (retry ocorre de fato) | `FlagChangeSubscriberUnitTest.java:66-79` — `assertEquals(2, connector.connections.size(), ...)` | uma segunda tentativa de conexão acontece após a falha | ✅ |
| FTR-03: reconexão usa jitter/backoff, não delay fixo | `ReconnectBackoffUnitTest.java:20-49` — bounds e crescimento por tentativa; `ReconnectBackoffUnitTest.java:57-61` — sementes diferentes divergem | delay nunca é um valor fixo, cresce com a tentativa, respeita `[base,max]` | ✅ |
| FTR-03: duas instâncias convergem no limite aprovado | `FlagChangeSubscriberConvergenceIT.java:60-73` — `assertFalse(instanceA.isConvergenceDegraded(), ...)`/`assertFalse(instanceB.isConvergenceDegraded(), ...)` e `assertTrue(..latency..compareTo(threshold) <= 0)` para duas instâncias reais contra Redis real | ambas as instâncias observam a mudança dentro do limite configurado | ✅ |
| FTR-03: convergência degradada emite alerta | `FlagChangeSubscriberConvergenceIT.java:79-96` — `assertTrue(instanceA.isConvergenceDegraded())` com limite `Duration.ZERO` | qualquer latência real ultrapassa um limite zero e é sinalizada | ✅ |
| FTR-03: latência é medida a partir do timestamp real de publish | `FlagChangeSubscriberUnitTest.java:128-140` — `assertTrue(latency.toMillis() >= 0 && latency.toMillis() < 5_000, ...)` | latência calculada é a diferença real entre publish e receive | ✅ |
| FTR-03: payload malformado não quebra invalidação, só perde a medição | `FlagChangeSubscriberUnitTest.java:143-155` — `assertTrue(invalidated.contains(...))` e `assertTrue(subscriber.lastConvergenceLatency().isEmpty())` | invalidação continua funcionando; convergência não é medida sem timestamp | ✅ |
| Done-when: restart/outage sem vazamento e convergência dentro do limite ou alerta | todas as linhas acima | cobertura fim a fim, unitária e contra Redis real | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `FlagChangeSubscriberUnitTest.java:66-79` | FTR-03 sem vazamento de conexão + reconexão | ✅ |
| `FlagChangeSubscriberUnitTest.java:83-142` | FTR-03 invalidação (nome/wildcard) + latência medida | ✅ |
| `FlagChangeSubscriberUnitTest.java:143-172` | FTR-03 payload malformado e limite de degradação | ✅ |
| `FlagChangeSubscriberUnitTest.java:175-186` | comportamento pré-existente de `close()` preservado, não uma nova AC isolada | ✅ |
| `ReconnectBackoffUnitTest.java:14-84` (6 testes) | FTR-03 bounds/crescimento/jitter do backoff | ✅ |
| `ChangeMessageUnitTest.java:9-42` (5 testes) | FTR-03 parsing do envelope de convergência | ✅ |
| `ConvergenceTrackerUnitTest.java:11-49` (5 testes) | FTR-03 fronteira exata do limite aprovado | ✅ |
| `FlagChangeSubscriberConvergenceIT.java:44-73,76-96` | FTR-03 convergência real multi-instância e alerta de degradação | ✅ |

**Adequacy review:** cobertura suficiente e necessária. `subscribeFailureAfterConnectClosesThePartialConnectionInsteadOfLeakingIt` é o teste de controle direto do bug original: contra a implementação antiga (`pubSub = redisClient.connectPubSub()` atribuído antes de `subscribe()`), a primeira conexão nunca seria fechada — a asserção `connections.get(0).closed.get()` mata exatamente essa regressão. Optei por um fake escrito à mão (`PubSubConnector`) em vez de tentar reproduzir "conectou mas falhou no subscribe" contra um Redis real, porque esse cenário é efetivamente inatingível com um Redis saudável — testá-lo contra Redis real seria um teste flaky ou inexistente, não mais realista. A convergência multi-instância, ao contrário, é testada contra Redis real com duas instâncias de `FlagChangeSubscriber` de verdade (não simuladas), porque é exatamente aí que round-trip de rede real importa. `aDegradedConvergenceLimitIsFlagged` usa `Duration.ZERO` como limite deliberadamente — não fabrica uma condição de outage, apenas prova que o caminho de alerta dispara sob latência real qualquer, o que uma asserção "não degradado" nunca provaria sozinha. Nenhuma asserção usa apenas contagem de chamada onde o estado resultante importa: `isConvergenceDegraded()`/`lastConvergenceLatency()` leem o estado real do tracker. Reverse-mapping (Check C): toda asserção nova mapeia a uma frase literal de FTR-03 (fecha conexão parcial, reconecta com jitter, convergência dentro do limite, alerta de degradação); nenhuma é especulativa. `CompositeFlagSource`/`RedisFlagSource` (T48) não foram tocados. Nenhum teste anterior foi removido, pulado ou enfraquecido; `FeatureDemoFlowIT`/`PilotIT` foram reexecutados como controle negativo e permanecem verdes, incluindo o teste que exercita o fluxo real admin->publish->subscribe->invalidate. Sem SPEC_DEVIATION: a mudança de formato do payload pub/sub (`nome|timestamp`) é uma decisão de engenharia não ditada literalmente pela spec, necessária para medir convergência sem inventar um canal separado; documentada aqui, com fallback explícito para payloads sem o envelope (nunca quebra a invalidação, só perde a medição).

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
**Status:** Complete

**Gate evidence:** `FlagAdminService.put` already had CAS via a Lua script, but `delete` was an unconditional `DEL` with no version check and no audit — a mutation without an audit entry could happen (`delete` never wrote one), and a concurrent delete could silently discard someone else's newer write. Extracted `store/VersionedFlagStore`, the single Redis choke point for both create/update and delete: `PUT_LUA`/`DELETE_LUA` each perform the CAS check and the `XADD` audit write (before/after/actor/version/timestamp/result) inside one `EVAL`, so a crash between "mutate" and "audit" is structurally impossible. `FlagAdminService.put`/`delete` now delegate to it and both require a non-blank `actor` up front (`requireActor`), rejecting an unauthenticated/anonymous caller before any Redis call. `FeatureAdminController.delete` accepts an optional `?version=`; when omitted it now reads the authoritative current version via the new `FlagAdminService.currentVersion` (added in this task) instead of computing it from the cached `FlagSource` resolver.

That last point was a real bug found while gate-checking this task, not a hypothetical: manually reproducing `PUT` (create, version 0→1) then `DELETE` with no `?version=` against a running `feature-demo` returned **409 "expected 0 but current is 0"** even though Redis genuinely held version 1 (confirmed with `redis-cli GET`). Root cause was `FlagDefinition`'s `objectMapper.readValue(json, FlagDefinition.class)` silently resolving to the record's secondary 9-arg "backward-compatible" constructor instead of the validating 11-arg canonical one — Micronaut Serde had no `@Creator` hint to disambiguate between the two public constructors, so `version`/`bucketingSalt` were dropped from every read (always defaulting to `0`/`null`) with no exception thrown. This pre-dates this task (the same broken `currentVersion()` body already existed in `FlagAdminService` before the T50 refactor, just never had a caller that depended on the number being correct — only the HTTP status code was ever asserted). Fixed by annotating the compact canonical constructor with `@io.micronaut.core.annotation.Creator` in `FlagDefinition.java`, verified by hand (`PUT` → `DELETE` round trip via `curl` against the real running app and real Redis) before writing the regression test below.

Full gate (`feature-control/gradlew test -PwithIT --no-daemon`, real Redis at `localhost:6379`) passed **128 total test cases** (0 failed) across `feature-control`, `feature-demo`, `pilot-app` — including all 9 pre-existing `FeatureDemoFlowIT` tests, which exercise the admin HTTP surface end to end and would not have caught the constructor bug before this task added a caller that depends on `currentVersion()`'s correctness.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| FTR-04: delete stale retorna conflito | `VersionedFlagStoreIT.java:167-177` — `assertEquals(-1L, result, ...)` para `expectedVersion=0` contra um valor real armazenado em `1`; `assertTrue(connection.sync().get(...) != null, ...)` | delete com versão desatualizada é rejeitado (`-1`) e a flag sobrevive | ✅ |
| FTR-04: mutação sem audit não ocorre (rejeitada não grava audit) | `VersionedFlagStoreIT.java:127-138` (create) e `:166-177` (delete) — `assertEquals(1, auditEntries().size(), ...)` permanece em 1 após a tentativa rejeitada | toda escrita rejeitada por CAS não deixa entrada de audit "fantasma" | ✅ |
| FTR-04: mutação aceita grava audit atomicamente (before/after/actor/version/result) | `VersionedFlagStoreIT.java:95-109` — `assertEquals("put", ...)`, `assertEquals("alice", ...)`, `assertEquals("", body.get("before"))`, `assertEquals(createdJson, body.get("after"))`, `assertEquals("1", body.get("version"))`, `assertEquals("ok", ...)`, `assertTrue(Long.parseLong(body.get("ts")) > 0, ...)` | todos os sete campos do registro de auditoria batem com o valor real da mutação aceita | ✅ |
| FTR-04: delete audita before=valor apagado, after vazio | `VersionedFlagStoreIT.java:152-164` — `assertEquals(v1Json, deleteEntry.get("before"))`, `assertEquals("", deleteEntry.get("after"))` | o registro de auditoria de delete reflete o valor real removido | ✅ |
| FTR-04: actor é autenticado (mutação anônima é rejeitada antes de tocar o store) | `FlagAdminServiceUnitTest.java:23-46` (5 testes) — `assertThrows(IllegalArgumentException.class, ...)` para put/delete com actor nulo, vazio ou em branco, usando `serviceWithNoStore()` (store `null`, provando que a rejeição ocorre antes de qualquer chamada Redis) | put/delete nunca prossegue sem um actor autenticado não vazio | ✅ |
| FTR-04: concorrência real — apenas um vencedor, apenas um audit | `VersionedFlagStoreIT.java:190-223` — 10 threads reais disputando a mesma versão esperada; `assertEquals(1, successes.get(), ...)`, `assertEquals(1, auditEntries().size(), ...)` | exatamente uma escrita concorrente vence e é auditada, nunca duas | ✅ |
| FTR-04 fim a fim: rota HTTP real usa CAS/audit corretos (regressão do bug de `currentVersion`) | `FeatureDemoFlowIT.java:136-150` (`optimisticConcurrencyReturns409`) e `:92-114` (`runtimeFlipViaAdminChangesDecision`) — reexecutados contra o app e Redis reais, `assertEquals(HttpStatus.CONFLICT, ...)` e round trip PUT→GET→DELETE→GET completo | a rota admin real (não só o store isolado) usa a versão correta para CAS/delete | ✅ |
| Done-when: delete stale conflito + mutação sem audit não ocorre + actor autenticado | todas as linhas acima | cobertura fim a fim, unitária, integração e HTTP real | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `VersionedFlagStoreIT.java:84-92,111-125` | FTR-04 create/update aceitos e auditados atomicamente | ✅ |
| `VersionedFlagStoreIT.java:94-109,151-164` | FTR-04 sete campos do registro de auditoria (put e delete) | ✅ |
| `VersionedFlagStoreIT.java:127-138,166-177` | FTR-04 CAS rejeita stale, sem audit fantasma, sem mutação | ✅ |
| `VersionedFlagStoreIT.java:140-149,179-187` | FTR-04 delete bem-sucedido remove a chave; delete-de-ausente é no-op auditado | ✅ |
| `VersionedFlagStoreIT.java:189-223` | FTR-04 concorrência real, um único vencedor | ✅ |
| `FlagAdminServiceUnitTest.java:23-46` | FTR-04 actor obrigatório antes de qualquer chamada ao store | ✅ |
| `FeatureDemoFlowIT.java:92-114,136-150` | FTR-04 regressão fim a fim do bug de `currentVersion` na rota HTTP real | ✅ |

**Adequacy review:** cobertura suficiente e necessária. Toda asserção de payload lê estado real — `XRANGE` do audit stream, `GET` da chave da flag, o corpo JSON real do registro de auditoria — nunca apenas que um método foi chamado. `staleDeleteReturnsConflictAndWritesNoAuditEntryAndLeavesTheFlagInPlace` é o teste de controle direto do requisito "delete stale retorna conflito": mata uma implementação que trocasse a checagem `expected ~= curVer` por incondicional. `concurrentCreatesAtTheSameExpectedVersionAllowExactlyOneWinner` prova a atomicidade contra o Redis real com 10 threads reais, não um mock — mata uma implementação que fizesse `GET` e `SET` como dois passos separados no lado do cliente em vez de um único `EVAL`. `FlagAdminServiceUnitTest` usa `serviceWithNoStore()` com `store=null` deliberadamente: se `requireActor` não short-circuitasse antes de tocar `store`, os cinco testes lançariam `NullPointerException` em vez do `IllegalArgumentException` esperado, o que os torna também um teste de controle da ordem de validação. `FeatureDemoFlowIT`/`PilotIT` foram reexecutados como controle negativo (128 casos totais, 0 falhas) — não são testes novos desta tarefa, mas sua reexecução prova que o fix do bug de `currentVersion` (achado durante o gate desta tarefa, não hipotético) não regrediu nenhum comportamento existente. Nenhum teste verifica comportamento do driver Lettuce ou do Micronaut Serde isoladamente. T51 (cardinalidade/PII) e T52 (publicação) não foram tocados aqui. SPEC_DEVIATION: nenhuma no comportamento pedido pela AC; o desvio foi um bug pré-existente e não relacionado (a ambiguidade de construtor do `FlagDefinition` na deserialização), corrigido porque esta tarefa foi a primeira a depender da correção numérica de `currentVersion()` — documentado acima com evidência de reprodução manual antes do fix.

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
**Status:** Complete

**Gate evidence:** Read `MicrometerDecisionListener` and `LoggingDecisionListener` before changing them and confirmed two FTR-05 gaps. (1) **Unbounded metric cardinality:** `flag`/`variant` were used as Prometheus tags with no bound at all — an admin-controlled source is normally small, but nothing enforced that, so a misconfigured or malicious source minting many distinct flag/variant names would permanently grow the series count. (2) **Raw PII in logs:** `LoggingDecisionListener` logged `context.bucketingKey()` — the JWT `userId` when authenticated — directly at DEBUG as `subject={}`; `FeatureContext.bucketingKey()`'s own javadoc documents it returns the userId, a real user identifier.

Added `metrics/CardinalityGuard`, a per-dimension bounded set (`platform.features.metric-cardinality-limit`, default 200): the first `limit` distinct values pass through unchanged, every value after that collapses to the literal `"other"`, so total series count for that dimension can never exceed `limit + 1` regardless of how much distinct input arrives. `MicrometerDecisionListener` now holds two independent guards (`flag`, `variant`) so a cardinality explosion on one dimension never crowds out the other's budget; `reason_kind` was already bounded (pre-existing, unchanged). Added `metrics/SubjectHasher` (SHA-256, truncated to a 12-hex-char token, `"none"` for null/blank) and wired it into `LoggingDecisionListener` in place of the raw bucketing key — deterministic (the same subject always hashes the same, so an operator can still correlate one subject's log lines) but irreversible, so no log line ever holds a real user/anon id again.

Full gate (`feature-control/gradlew test -PwithIT --no-daemon`, real Redis at `localhost:6379`) passed **16 new tests** (`CardinalityGuardUnitTest` 6, `SubjectHasherUnitTest` 5, `LoggingDecisionListenerUnitTest` 2, `MicrometerDecisionListenerUnitTest` 3) above the ≥8 requested, plus all pre-existing `feature-control`/`feature-demo`/`pilot-app` tests unchanged and green (including `FeatureDemoFlowIT.decisionMetricsAreExposed`, which exercises `MicrometerDecisionListener` end to end through the real HTTP/metrics stack).

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| FTR-05: alta cardinalidade sintética de nomes de flag permanece bounded (métricas) | `MicrometerDecisionListenerUnitTest.java:34-49` — 5.000 nomes sintéticos de flag; `assertTrue(registry.getMeters().size() <= 51, ...)` e `assertEquals(4_950.0, ...overflow series...)` | o número de séries nunca ultrapassa `limit+1` independente do volume de entrada distinta | ✅ |
| FTR-05: alta cardinalidade sintética de variantes permanece bounded independentemente do flag (métricas) | `MicrometerDecisionListenerUnitTest.java:52-64` — 100 variantes sintéticas em um único flag estável; `assertTrue(registry.getMeters().size() <= 11, ...)` | o guard de `variant` limita mesmo quando `flag` nunca varia | ✅ |
| FTR-05: cardinalidade sintética de nomes/valores permanece bounded (unidade pura) | `CardinalityGuardUnitTest.java:44-51` — 10.000 valores distintos; `assertEquals(50, guard.size(), ...)` | o conjunto rastreado nunca cresce além do limite configurado | ✅ |
| FTR-05: concorrência real não rompe o limite | `CardinalityGuardUnitTest.java:64-91` — 16 threads reais, 16.000 valores distintos; `assertTrue(guard.size() <= 20 + threads, ...)` | mesmo sob corrida na fronteira, o tamanho rastreado permanece próximo do limite, nunca ilimitado | ✅ |
| FTR-05: user/bucketing key nunca aparece no log emitido (scan de PII) | `LoggingDecisionListenerUnitTest.java:39-53` — captura real via `ListAppender` do Logback; `assertFalse(rendered.contains(RAW_USER_ID), ...)` sobre a linha de log de fato emitida, não o código-fonte | um scan sobre o log real não encontra o identificador bruto | ✅ |
| FTR-05: o token hasheado substitui o subject no log, de forma determinística | `LoggingDecisionListenerUnitTest.java:56-66` — `assertTrue(rendered.contains("subject=" + SubjectHasher.hash(RAW_USER_ID)), ...)`; `SubjectHasherUnitTest.java:22-25` — mesmo input sempre produz o mesmo token | correlação por subject continua possível sem expor o identificador real | ✅ |
| FTR-05: o hash é irreversível o suficiente para não conter o valor bruto | `SubjectHasherUnitTest.java:14-20` — `assertFalse(token.contains(raw), ...)` para um e-mail real como input | o token nunca contém a substring do valor original | ✅ |
| Done-when: alta cardinalidade sintética bounded + scan de logs sem PII | todas as linhas acima | cobertura unitária, concorrente e via captura real de log/registry | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `CardinalityGuardUnitTest.java:16-51` | FTR-05 admissão dentro do limite, repetição sempre admitida, overflow além do limite | ✅ |
| `CardinalityGuardUnitTest.java:44-51,64-91` | FTR-05 cardinalidade sintética bounded, sequencial e concorrente | ✅ |
| `CardinalityGuardUnitTest.java:57-62` | FTR-05 valor nulo nunca é rastreado (não infla o conjunto) | ✅ |
| `SubjectHasherUnitTest.java:14-40` | FTR-05 hash determinístico, distinto por input, sem PII bruta, formato fixo | ✅ |
| `LoggingDecisionListenerUnitTest.java:31-66` | FTR-05 scan real de log sem PII + token hasheado presente | ✅ |
| `MicrometerDecisionListenerUnitTest.java:20-64` | FTR-05 poucas séries para poucos flags reais + bounded sob alta cardinalidade sintética em ambas as dimensões | ✅ |

**Adequacy review:** cobertura suficiente e necessária. `theEmittedLogLineNeverContainsTheRawBucketingKey` lê a linha de log real capturada via `ListAppender` (não o código-fonte nem um mock de `Logger`) — é o teste de controle direto do requisito "scan de logs não encontra PII": contra a implementação antiga (`context.bucketingKey()` interpolado direto), essa asserção falharia imediatamente. `syntheticHighCardinalityFlagNamesCollapseToABoundedSeriesCount` usa um `SimpleMeterRegistry` real do Micrometer, não um mock — conta séries de verdade e soma o contador real da série de overflow (4.950 = 5.000 - 50 admitidos), o que mata uma implementação que só limitasse a exibição sem de fato agregar no mesmo tag. `syntheticHighCardinalityVariantNamesCollapseIndependentlyOfFlagNames` prova que os dois guards são independentes — mata uma implementação com um único guard compartilhado entre `flag` e `variant`, que deixaria uma dimensão "roubar" o orçamento da outra. `concurrentAdmissionOfManyDistinctValuesStaysBounded` prova o bound sob concorrência real (16 threads), não apenas sequencial. Nenhuma asserção verifica apenas que um método foi chamado: todas leem o estado real (linha de log renderizada, contagem real do registry, tamanho real do `Set` rastreado). T50 (CAS/audit) e T52 (publicação) não foram tocados aqui; `FeatureResolver`/`allowlistMatch` já não embutiam PII na `reason` (confirmado por leitura antes de qualquer mudança — só literais como `allowlist:user`), então nenhuma mudança foi necessária ali. Nenhum teste anterior foi removido, pulado ou enfraquecido. SPEC_DEVIATION: nenhuma — "allowlist/buckets" da spec foi implementado como um limite de cardinalidade bounded (bucket-style overflow) em vez de uma allowlist estática de nomes, porque a spec não define uma lista fixa de flags/variantes válidas (ela é dinâmica, definida em YAML/Redis por app) — um bound estrutural sobre o número de séries é o que a AC realmente exige ("permanece bounded"), documentado aqui.

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
**Status:** Complete

**Gate evidence:** `feature-control`'s root `build.gradle` already had `publishLibraryToLocalBuildRepository`/`verifyLocalPublication` (from T46) verifying POM/jar/sources/Javadoc exist, but nothing exercised the artifact as a consumer would, and nothing caught a breaking public-API change. Added `feature-control/consumer-fixture` (a standalone Gradle build, same shape as `payment-contracts/consumer-fixture`) whose `build.gradle` resolves `com.example.platform:feature-control:0.1.0` through an `exclusiveContent` repository pointed at the boundary-local publication (`-PartifactRepository=...`) — `com.example.platform` is reserved to that repository, so no `project(...)` source substitution is possible even by accident. `PublishedArtifactApiTest` exercises the pure (no-DI-required) part of the public surface — `FlagDefinition`, `FeatureContext`, `Bucketer`, `FeatureDecision` — using only that published jar.

For "breaking API falha," added `scripts/verify_api_surface.py`: it runs `javap -public` against 8 promised classes (the ones the fixture depends on, plus `Variant`/`FlagType`/the two new T51 metrics classes) and diffs the result against a committed baseline (`consumer-fixture/api-surface-baseline.txt`, generated once from the real jar via `--write-baseline`); a baseline member missing from the current jar fails the gate, a new member (growth) does not. `scripts/check_consumer_fixture.py` (adapted from the `payment-contracts` original for one artifact/one GAV) verifies the fixture's `build.gradle` declares the GAV, reserves the group via `exclusiveContent`, and never reads cross-boundary source, plus that the four expected artifact files exist with matching POM coordinates. `scripts/verify-consumer-fixture.sh` orchestrates all of it end to end: publish → `verifyLocalPublication` → `check_consumer_fixture.py` → `verify_api_surface.py` → fixture test against the real repository (must pass) → fixture compile against an empty repository (must fail, proving artifact-only resolution, same negative-check pattern as `payment-contracts`).

Ran the full gate (`bash scripts/verify-consumer-fixture.sh`, then `./gradlew build -PwithIT --no-daemon` at the boundary root) and the Python suite (`python3 -m unittest discover -s scripts -p "test_*.py"`): **7 checks pass** — publish+`verifyLocalPublication` (4 artifact files), `check_consumer_fixture.py` (GAV/coordinates/no-substitution), `verify_api_surface.py` (8 classes, 0 breaking changes), the fixture's 5 `PublishedArtifactApiTest` cases against the real published jar, the missing-repository negative check (fixture compile fails without a published artifact), and the 11-case Python `unittest` suite (4 `check_consumer_fixture` + 7 `verify_api_surface`) — well above the ≥5 requested. `./gradlew build -PwithIT` at the root stays green (30 tasks, `BUILD SUCCESSFUL`).

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| Fixture usa somente GAV publicado (sem project substitution) | `test_consumer_fixture.py:24-25` — `assertEqual([], consumer_fixture.fixture_errors(FIXTURE))` sobre o `build.gradle`/`settings.gradle` reais do fixture | nenhum `project(...)`/`includeBuild` e `exclusiveContent` reserva o grupo | ✅ |
| Fixture falha sem artefato publicado (round trip real) | `verify-consumer-fixture.sh:20-27` — `gradlew compileTestJava -PartifactRepository=<vazio>` deve sair não-zero; script aborta com `ERROR` se sair 0 | resolução artifact-only comprovada contra um repositório Maven real vazio | ✅ |
| Fixture compila e roda contra o artefato publicado real | `verify-consumer-fixture.sh:15-16` + `PublishedArtifactApiTest.java` (5 testes) — execução real do `gradlew test` do fixture contra a publicação real | o jar publicado é de fato consumível como uma app externa consumiria | ✅ |
| Artefatos esperados existem (POM/jar/sources/Javadoc) | `check_consumer_fixture.py:23-45` (`artifact_errors`), exercido por `test_consumer_fixture.py:27-70` (3 testes) e pela execução real via `verifyLocalPublication` no script | os quatro arquivos existem com coordenadas de POM corretas | ✅ |
| Breaking API falha (remoção de membro público é detectada) | `test_consumer_fixture.py:96-104` — `test_a_removed_public_method_is_reported_as_breaking`: `assertEqual(1, len(errors))`, `assertIn("baz()", errors[0])` | um membro presente na baseline e ausente do jar atual é reportado como quebra | ✅ |
| Crescimento de API (novo método) não é falsamente reportado como quebra | `test_consumer_fixture.py:88-94` — `test_a_new_method_in_current_is_not_a_breaking_change`: `assertEqual([], ...)` | apenas remoção/mudança quebra; adição nunca falha o gate | ✅ |
| A baseline commitada reflete de fato o jar publicado hoje | `test_consumer_fixture.py:130-136` + execução real de `verify_api_surface.py` no script contra o jar real: `api-surface: PASS (8 promised classes, no breaking change)` | a baseline não é um artefato desatualizado; casa com o jar publicado agora | ✅ |
| Done-when: fixture artifact-only + breaking API falha + artefatos existem + ≥5 checks | todas as linhas acima (7 checks reais) | cobertura via execução real do gate (Gradle+Python), não simulação | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `test_consumer_fixture.py:24-70` (`ConsumerFixtureTest`, 4 testes) | fixture artifact-only + artefatos publicados + coordenadas de POM | ✅ |
| `test_consumer_fixture.py:88-104` | crescimento de API não quebra; remoção de membro quebra | ✅ |
| `test_consumer_fixture.py:106-112` | remoção de uma classe prometida inteira também quebra | ✅ |
| `test_consumer_fixture.py:114-121,123-136` | round trip do formato da baseline; baseline commitada casa com as classes prometidas | ✅ |
| `test_consumer_fixture.py:138-148` | parsing correto da saída real do `javap` (cabeçalho de classe descartado, membros extraídos) | ✅ |
| `PublishedArtifactApiTest.java` (5 testes) | API pública é de fato consumível a partir do GAV publicado real | ✅ |

**Adequacy review:** cobertura suficiente e necessária. `test_a_removed_public_method_is_reported_as_breaking`/`test_a_removed_promised_class_is_reported_as_breaking` são os testes de controle diretos do requisito "breaking API falha": ambos operam sobre a função pura `breaking_changes`, não sobre uma simulação de rebuild — mata uma implementação que só comparasse contagens de membros em vez de nomes/assinaturas exatas. `test_a_new_method_in_current_is_not_a_breaking_change` prova o lado oposto necessário: um gate que falhasse em qualquer diferença (não só remoção) bloquearia releases legítimos que só adicionam API, o que a spec não pede. O gate real (`verify-consumer-fixture.sh`) foi executado de ponta a ponta contra Gradle e um repositório Maven real — não apenas os testes Python unitários — incluindo a negação real (repositório vazio faz o `compileTestJava` do fixture falhar de verdade). `test_the_committed_baseline_matches_every_promised_class` impede que a baseline commitada fique órfã (um arquivo desatualizado que nunca mais detectaria uma quebra real). Nenhuma asserção verifica apenas contagem de chamada; todas leem o resultado real (lista de erros, arquivo de baseline, saída real do `javap` contra o jar publicado de verdade). T51 (telemetria) e T53 (docs/segurança dos exemplos) não foram tocados aqui. SPEC_DEVIATION: nenhuma no requisito em si; a técnica escolhida para "binary compatibility" foi um diff de superfície pública via `javap` com baseline commitada (em vez de uma ferramenta como japicmp, que não está presente em nenhum lugar do repositório) — mais simples, sem nova dependência de build, e suficiente para o que a AC pede (detectar remoção/mudança de um membro público prometido); documentado aqui.

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
**Commit:** `fix(feature-control): isolate nonproduction examples` (not `security(...)` — not an allowed Conventional Commit type per `check_commit.py`; see T32's note)
**Status:** Complete

**Gate evidence:** Read `DevTokenController`/`FeatureAdminController`/`FeatureDemoController` before changing anything and confirmed the SEC-01/SEC-02 gap: `DevTokenController`'s Javadoc says "Not for production," but nothing enforced it — no `@Requires(notEnv = "prod")` on any controller, no startup guard at all. `feature-demo`/`pilot-app` are `NON_PRODUCTION` only by AD-005's classification, not structurally.

Added `NonProductionExampleGuard` to both examples (`@Context @Requires(env = "prod")`, throws `ConfigurationException` unconditionally in its constructor — no valid production configuration exists for either app, unlike a real service's `ProductionAcceptanceGuard`, which validates config and proceeds). The whole `ApplicationContext` fails to initialize under `env=prod`, which is a strict superset of excluding individual demo/admin routes from the bean graph (SEC-02): nothing starts, so nothing is reachable. Documented the decision and the alternatives considered (per-controller `@Requires(notEnv="prod")`, runtime 404s, docs-only) in [ADR-0001](docs/adr/0001-nonproduction-example-startup-guard.md).

Created the full doc set this boundary was missing (DOC-01..04): `README.md`, `AGENTS.md`, `docs/{architecture,configuration,security,operations,testing}.md`, `docs/adr/{README,0001-nonproduction-example-startup-guard}.md`, plus `scripts/{validate_docs.py,test_docs.py,verify-docs.sh}` (adapted from `async-redis-service`'s pattern, scoped down to what a library + two `NON_PRODUCTION` examples actually needs — no contracts/observability/performance docs or ops runbooks, since this boundary has no HTTP contract of its own, no on-call surface and no dedicated capacity gate; noted as a deliberate scope reduction, not an oversight). Added `.github/workflows/ci.yml` (unit+build, integration against a real Redis service container, docs, `verify-consumer-fixture.sh`, `git diff --check`) — not auto-discovered by GitHub yet during the transitional monorepo (same as `async-redis-service`'s local CI file), ready to copy into `.github/workflows` when this boundary becomes its own repository.

Full gate (`./gradlew build -PwithIT --no-daemon`, `scripts/verify-docs.sh`, `bash scripts/verify-consumer-fixture.sh`) passed: **6 new security ITs** (`NonProductionGuardIT`, 3 per example — refusal under `prod` alone, refusal under `prod` combined with another environment, success under a non-`prod` environment as a regression control) above the ≥6 requested, plus the docs gate (`feature-control-docs: PASS`) and the consumer-fixture gate (`feature-control-consumer-gate: PASS`) both green, on top of every pre-existing test unchanged.

| Critério / requisito | Evidência `file:line` e assertion | Resultado definido | Coberto? |
| --- | --- | --- | --- |
| SEC-01: recusa inicialização em produção (sem config válida possível) | `NonProductionGuardIT.java:26-34` (ambos exemplos) — `assertThrows(RuntimeException.class, ...)` com `env("prod")`; `assertTrue(messages(failure).contains("NON_PRODUCTION example"), ...)` | o `ApplicationContext` nunca termina de subir sob `prod` | ✅ |
| SEC-01: recusa mesmo com `prod` combinado a outro environment | `NonProductionGuardIT.java:37-46` (ambos exemplos) — `environments("prod", "cloud")`, mesma asserção | `@Requires(env="prod")` dispara mesmo quando `prod` não é o único environment ativo | ✅ |
| SEC-02: rotas demo/admin não aparecem em PRD (bean graph e superfície HTTP) | mesma evidência acima — falha de contexto implica bean graph vazio e servidor HTTP nunca sobe | nenhuma rota é servida sob `prod`, condição estritamente mais forte que exclusão seletiva | ✅ |
| SEC-01/SEC-02: o guard não bloqueia uso legítimo (controle de regressão) | `NonProductionGuardIT.java:49-56` (ambos exemplos) — `ApplicationContext.run(EmbeddedServer.class, ..., "guard-it")`; `assertTrue(server.isRunning())` | a mesma configuração sobe normalmente sob um environment não-`prod` | ✅ |
| SEC-04: endpoint admin tem política AuthN/AuthZ documentada e testada | `docs/security.md` seção "Mutações admin (FTR-04)" (documentada) + `FeatureDemoFlowIT.java` `adminRequiresAdminRole()` (pré-existente, testada) | `ROLE_ADMIN` exigido via `intercept-url-map`, documentado e coberto por teste | ✅ |
| DOC-01..04: README/AGENTS/docs proporcionais/ADR numerado | `scripts/validate_docs.py` execução real: `feature-control-docs: PASS (10 required documents, links, claims, ADR)`; `test_docs.py:12-13` — `assertEqual([], validate_docs.validate(validate_docs.ROOT))` contra a árvore real | todos os documentos exigidos existem, sem link quebrado, ADR com as 5 seções e `Status: Accepted` | ✅ |
| Done-when: rotas não aparecem em PRD + label + docs/CI/publication gates + ≥6 security ITs | todas as linhas acima, mais `verify-consumer-fixture.sh` (`feature-control-consumer-gate: PASS`, T52) e `.github/workflows/ci.yml` | cobertura via execução real de todos os três gates (build, docs, fixture) | ✅ |

| Assertion | Mapeia para | Keep? |
| --- | --- | --- |
| `feature-demo/.../config/NonProductionGuardIT.java` (3 testes) | SEC-01/SEC-02 recusa de startup em prod + controle de regressão | ✅ |
| `pilot-app/.../config/NonProductionGuardIT.java` (3 testes) | mesmo requisito, exemplo irmão | ✅ |
| `scripts/test_docs.py:12-18` (2 testes) | DOC-01..04 documentação real sem erros + detecção de documento ausente | ✅ |

**Adequacy review:** cobertura suficiente e necessária. `startupIsRefusedUnderTheProdEnvironment`/`startupIsRefusedWhenProdIsCombinedWithAnotherEnvironment` são os testes de controle diretos do requisito "rotas não aparecem em PRD": ambos sobem um `ApplicationContext` real (não um mock), exatamente como um deploy real faria. `startupSucceedsUnderANonProdEnvironmentWithTheSameProperties` é o teste de controle inverso, necessário — sem ele, uma implementação que recusasse startup incondicionalmente (não só sob `prod`) passaria nos dois primeiros testes e quebraria todo uso legítimo do exemplo, sem que nada aqui detectasse isso. `test_the_real_documentation_tree_has_no_errors` roda contra a árvore de documentos de verdade, não uma fixture sintética — mata uma implementação de `validate_docs.py` que sempre retornasse `[]`. Nenhuma asserção verifica só contagem de chamada; todas leem o resultado real (`server.isRunning()`, a cadeia de causas da exceção, a lista real de erros do validador). T50/T51/T52 não foram tocados aqui. SPEC_DEVIATION: o conjunto de documentos DOC-03 foi reduzido em relação ao padrão usado por `async-redis-service` (sem `contracts.md`/`observability.md`/`performance.md`/`ops/runbooks`) porque este boundary não tem contrato HTTP próprio para versionar, não tem on-call real (os exemplos não servem tráfego real) e não tem gate de capacidade dedicado — "proporcional ao tipo de projeto" é o texto literal de DOC-03; documentado em `docs/README.md`.

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
