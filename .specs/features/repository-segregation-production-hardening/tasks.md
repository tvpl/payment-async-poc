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
**Commit:** `fix(feature-control): isolate nonproduction examples` (not `security(...)` — not an allowed Conventional Commit type per `check_commit.py`; see T32's note)

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
