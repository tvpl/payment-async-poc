# Segregação de Repositórios e Hardening de Produção

## Problem Statement

O workspace reúne oito módulos com responsabilidades e ciclos de vida diferentes em um único build Gradle, um único `Dockerfile`, um único Compose e uma documentação central que mistura três capacidades arquiteturalmente distintas. Essa estrutura impede builds, releases, documentação, operação e evolução independentes, além de esconder gaps de segurança, durabilidade, escalabilidade e testes atrás de um baseline unitário verde.

A mudança criará fronteiras prontas para futura extração em repositórios, preservará contratos externos durante a migração e corrigirá ou classificará explicitamente os gaps que impedem uso em produção. Componentes didáticos permanecerão identificados como não produtivos.

## Baseline Evidence

- O build raiz declara oito subprojetos em `settings.gradle`; seis módulos dependem diretamente de outros projetos Gradle por `project(':...')`.
- Um único `Dockerfile` compila seis aplicações e usa nomes de JAR com versão fixa; não há `.dockerignore` nem usuário de runtime não privilegiado.
- O Compose raiz concentra aplicações, Kafka, Redis, PostgreSQL, Apicurio e observabilidade. Apicurio e `pilot-app` disputam a porta host `8085` em `docker-compose.yml:87` e `docker-compose.yml:389`.
- O CI executa somente as integrações dependentes de Redis. Os ITs Kafka/PostgreSQL de `api-service` e `sbus-service` não aparecem em `.github/workflows/ci.yml:38`.
- `core-mock` possui 195 linhas Java e nenhum teste. O fluxo principal possui um IT na API e um no SBUS.
- `DevTokenController` não possui restrição de ambiente em `api-service/src/main/java/com/example/payments/api/auth/DevTokenController.java:22` nem em `feature-demo/src/main/java/com/example/platform/featuredemo/auth/DevTokenController.java:24`. A configuração HS256 base continua presente quando `application-prod.yml` acrescenta JWKS.
- Todos os management endpoints da API e do SBUS são habilitados e não sensíveis em `api-service/src/main/resources/application.yml:124` e `sbus-service/src/main/resources/application.yml:101`.
- A API reserva idempotência antes de publicar, mas não vincula a chave ao hash do payload nem compensa a reserva/status quando a publicação falha em `api-service/src/main/java/com/example/payments/api/service/ApiPaymentService.java:74`.
- A DLQ da outbox é publicada depois de a linha ser marcada `FAILED`; uma falha nessa publicação apenas gera log em `sbus-service/src/main/java/com/example/payments/sbus/outbox/OutboxDispatcher.java:88`.
- O retry do SBUS limita o sono a cinco segundos e processa mesmo quando `not-before` continua no futuro em `sbus-service/src/main/java/com/example/payments/sbus/kafka/RetryConsumer.java:69`.
- O serviço Redis limita o stream por `MAXLEN` sem proteger entradas pendentes, reutiliza nomes de consumidores entre instâncias e pode bloquear indefinidamente aguardando conexão do pool antes de iniciar o timeout de negócio.
- A documentação afirma que o serviço Redis está pronto para carga real em `docs/17-async-sync-redis.md:152`, conclusão que o código e os testes atuais não sustentam.
- `./gradlew test --no-daemon` passou em 2026-08-08; `docker compose config -q` também passou. O build reportou uma depreciação incompatível com versões futuras do Gradle.

## Goals

- [ ] Tornar cada fronteira de produto ou biblioteca compilável, testável, documentável e versionável sem depender do build raiz.
- [ ] Isolar toda infraestrutura local compartilhada e observabilidade em `/sandbox`.
- [ ] Preservar os contratos HTTP, Kafka, Avro e de persistência durante uma migração incremental e reversível.
- [ ] Revisar todas as fronteiras e eliminar ou planejar com gate explícito os gaps de produção.
- [ ] Definir e provar uma meta de capacidade de 10.000 requisições por minuto sustentadas, com rajada de 20.000 por minuto durante 60 segundos, usando um ambiente de referência documentado.
- [ ] Criar documentação, ADRs e instruções de IA locais que impeçam acoplamentos e decisões obsoletas de reaparecer.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Criar repositórios remotos ou reescrever histórico Git | Exige autorização e estratégia organizacional separadas; esta mudança prepara fronteiras locais extraíveis. |
| Push, deploy, publicação externa de artefatos ou alteração em produção | São efeitos remotos e exigem autorização explícita posterior. |
| Escolher cloud, Kubernetes, service mesh ou fornecedores gerenciados | O desenho será portável e registrará requisitos, não uma plataforma ainda não escolhida. |
| Certificar capacidade sem ambiente e massa de teste definidos | A feature cria o modelo, os testes e o ambiente de referência; a certificação depende da execução nesse ambiente. |
| Transformar `core-mock`, `feature-demo` ou `pilot-app` em serviços produtivos | São simulador e exemplos; receberão limites e documentação explícitos de não produção. |
| Quebrar ou renomear endpoints, tópicos, schemas ou tabelas por conveniência | Compatibilidade é requisito; qualquer quebra futura precisará de versão e ADR próprios. |
| Criar imagens Docker para bibliotecas sem processo de runtime | `payment-contracts` e `feature-control` serão artefatos Maven, não containers artificiais. |

---

## Assumptions & Open Questions

Toda ambiguidade está resolvida por padrão proposto ou registrada aqui para confirmação.

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Granularidade das fronteiras | Sete raízes: `payment-contracts`, `payment-api`, `payment-sbus`, `payment-core-mock`, `feature-control`, `async-redis-service` e `sandbox`; `feature-demo` e `pilot-app` ficam como exemplos dentro de `feature-control` | Separa releases reais sem promover cada exemplo didático a produto/repositório. | y |
| Estado do Git durante a migração | Continuar em um único repositório local até todos os gates passarem | Permite moves rastreáveis e rollback antes da futura extração de histórico. | y |
| Dependências entre fronteiras | Consumir `payment-contracts` e `feature-control` como artefatos Maven versionados; composite build local apenas como conveniência opcional | Reproduz o comportamento de repositórios separados e elimina dependência oculta de source tree. | y |
| Compose por aplicação | Cada aplicação terá Compose apenas da própria aplicação e usará uma rede externa criada pelo `/sandbox`; nenhuma aplicação subirá banco, broker ou observabilidade | Atende ao isolamento pedido sem duplicar infraestrutura. | y |
| Compose do sandbox | Um Compose de infraestrutura compartilhada e perfis separados para observabilidade e ferramentas de inspeção | Mantém startup enxuto e permite diagnóstico completo quando necessário. | y |
| Meta de carga | 10.000 req/min por 15 minutos e rajada de 20.000 req/min por 60 segundos | Concretiza “milhares por minuto” em um gate mensurável e ainda ajustável. | y |
| Política sob saturação | Admitir somente até a capacidade segura; excedente recebe `429`/`202` conforme contrato, sem perda silenciosa de requisição aceita | Buffer não cria capacidade e backlog ilimitado não é escalabilidade. | y |
| Compatibilidade | Preservar contratos externos e migrations append-only; mudanças incompatíveis exigem versionamento paralelo | Reduz risco operacional e permite migração por etapas. | y |
| Idioma e convenções | Documentação operacional em pt-BR; código, nomes técnicos e commits em inglês | Mantém o padrão atual sem prejudicar interoperabilidade técnica. | y |
| Remoção documental | Remover somente após existir substituto correto e após link/claim validation | Evita perder conhecimento válido junto com conteúdo obsoleto. | y |
| ADRs | ADRs numerados e locais à fronteira; decisões que afetam todo o workspace também entram em `.specs/STATE.md` | Mantém contexto perto do código e memória transversal pequena. | y |

**Open questions:** none - all unresolved choices are logged above for confirmation.

---

## User Stories

### P1: Fronteiras prontas para repositórios separados ⭐ MVP

**User Story**: Como mantenedor da plataforma, quero raízes autossuficientes por produto ou biblioteca para que cada fronteira possa ser extraída, versionada, construída e entregue sem depender do monorepo atual.

**Why P1**: Esta é a finalidade principal da reorganização e condiciona todo o restante.

**Acceptance Criteria**:

1. **ORG-01** — The workspace SHALL conter exatamente as fronteiras aprovadas na seção de assumptions, cada uma com responsabilidade e ownership documentados.
2. **ORG-02** — WHEN o build de uma fronteira for executado a partir de sua própria raiz THEN a fronteira SHALL compilar e testar sem ler `settings.gradle`, `build.gradle`, wrapper ou fontes de outra raiz.
3. **ORG-03** — The deployable application SHALL possuir `settings.gradle`, build, Gradle wrapper, `Dockerfile`, `.dockerignore`, `compose.yaml`, `.env.example`, README, documentação, CI e `AGENTS.md` próprios.
4. **ORG-04** — The published library SHALL possuir build, Gradle wrapper, publicação Maven, política de compatibilidade, README, documentação, CI e `AGENTS.md` próprios, sem imagem ou Compose de runtime sem processo executável.
5. **ORG-05** — WHEN uma aplicação consumir um contrato ou biblioteca compartilhada THEN a aplicação SHALL declarar uma versão publicada e verificável, sem `project(':...')` entre fronteiras.
6. **ORG-06** — The `payment-contracts` boundary SHALL conter contratos de evento e compatibilidade Avro, sem rate limiter, controller, persistência ou regra de aplicação.
7. **ORG-07** — The service-specific runtime concern SHALL permanecer no serviço proprietário ou em biblioteca explicitamente versionada, sem uso de um diretório `common` genérico.
8. **ORG-08** — WHILE a migração estiver em andamento, the workspace SHALL manter um gate de equivalência que detecte perda de arquivo, contrato, teste, migration, dashboard ou script ainda válido.

**Independent Test**: Em checkout limpo, executar os gates de cada raiz isoladamente e provar que nenhuma referência de build cruza as fronteiras.

---

### P1: Sandbox único de infraestrutura compartilhada ⭐ MVP

**User Story**: Como desenvolvedor, quero uma pasta `/sandbox` responsável somente pela infraestrutura local comum para que aplicações independentes compartilhem Kafka, Redis, PostgreSQL, registry e observabilidade sem duplicação.

**Why P1**: A separação perde valor se cada aplicação reproduzir a mesma infraestrutura ou se o sandbox voltar a incorporar código de produto.

**Acceptance Criteria**:

1. **SBX-01** — The `/sandbox` boundary SHALL possuir Compose, `.env.example`, Makefile ou task runner, smoke de infraestrutura, documentação operacional e `AGENTS.md` próprios.
2. **SBX-02** — The `/sandbox` Compose SHALL conter somente infraestrutura compartilhada, observabilidade e ferramentas locais de inspeção, sem build ou fonte de aplicação.
3. **SBX-03** — WHEN uma aplicação for iniciada por seu Compose THEN a aplicação SHALL conectar-se à rede externa e aos endpoints documentados do sandbox sem criar Kafka, Redis, PostgreSQL, registry, Jaeger, Prometheus ou Grafana adicionais.
4. **SBX-04** — IF duas definições tentarem publicar a mesma porta host THEN the sandbox validation SHALL falhar antes do startup.
5. **SBX-05** — WHERE observabilidade estiver habilitada, the sandbox SHALL carregar somente infraestrutura comum e artefatos de observabilidade pertencentes às aplicações por mecanismo documentado e versionável.
6. **SBX-06** — IF o sandbox não estiver saudável THEN an application startup or smoke gate SHALL reportar a dependência indisponível com diagnóstico acionável, sem declarar o ambiente pronto.

**Independent Test**: Subir o sandbox sozinho, validar health de cada dependência e iniciar cada aplicação separadamente na rede compartilhada.

---

### P1: Hardening de segurança e operação ⭐ MVP

**User Story**: Como responsável por produção, quero perfis seguros e limites operacionais explícitos para impedir que facilidades da PoC sejam promovidas acidentalmente.

**Why P1**: Há caminhos de autenticação de desenvolvimento e endpoints de gerenciamento que hoje podem permanecer ativos no profile de produção.

**Acceptance Criteria**:

1. **SEC-01** — WHILE o profile de produção estiver ativo, the application SHALL recusar inicialização quando segredo, issuer, audience, credencial ou endpoint obrigatório estiver ausente ou usar default de desenvolvimento.
2. **SEC-02** — WHILE o profile de produção estiver ativo, the application SHALL excluir emissores de token, usuários simulados, failure hooks e endpoints de demonstração do bean graph e da superfície HTTP.
3. **SEC-03** — WHEN um JWT for aceito em produção THEN the application SHALL validar assinatura assimétrica, issuer, audience, expiração e política de clock skew definidas.
4. **SEC-04** — The business, admin and internal endpoint SHALL possuir política AuthN/AuthZ documentada e testada; o endpoint interno SHALL usar identidade de serviço ou controle equivalente.
5. **SEC-05** — The management endpoint SHALL expor anonimamente apenas liveness/readiness mínimos; métricas e detalhes SHALL seguir a política de rede e autenticação definida.
6. **SEC-06** — The repository SHALL versionar `.env.example` sem segredos e SHALL ignorar `.env` real.
7. **SEC-07** — The runtime container SHALL executar como usuário não privilegiado, usar base mínima suportada, healthcheck sem pacote desnecessário e configurações de filesystem/capabilities documentadas.
8. **SEC-08** — WHEN CI produzir artefato ou imagem THEN CI SHALL gerar inventário de dependências, executar análise de vulnerabilidades e bloquear severidade conforme política aprovada.

**Independent Test**: Iniciar cada aplicação em profile produtivo com combinações válidas e inválidas e verificar bean graph, rotas, autenticação e exposição de management.

---

### P1: Correção, durabilidade e resiliência do fluxo Kafka ⭐ MVP

**User Story**: Como operador do fluxo de pagamento, quero garantias testadas de idempotência, retry, outbox e recuperação para que uma requisição aceita não seja perdida ou finalizada de forma inconsistente.

**Why P1**: Pagamento, concorrência, estado e dependências externas tornam garantias implícitas insuficientes.

**Acceptance Criteria**:

1. **PAY-01** — WHEN uma idempotency key for usada pela primeira vez THEN the API SHALL associá-la atomicamente ao request id e ao fingerprint canônico do payload pelo período configurado.
2. **PAY-02** — IF a mesma idempotency key for reutilizada com payload diferente THEN the API SHALL responder conflito determinístico e SHALL publicar zero novo evento.
3. **PAY-03** — IF a publicação inicial falhar THEN the API SHALL deixar estado recuperável e retry-safe, sem reserva órfã que simule processamento até expirar.
4. **PAY-04** — WHEN estado de negócio do SBUS mudar THEN the SBUS SHALL persistir a linha de outbox correspondente na mesma transação local.
5. **PAY-05** — WHILE um evento estiver `IN_PROGRESS`, the outbox SHALL possuir lease, ownership e recuperação que permitam múltiplas instâncias sem publicação concorrente da mesma claim.
6. **PAY-06** — IF um publish Kafka for confirmado e o processo cair antes de marcar a outbox THEN downstream consumers SHALL tratar a repetição sem alterar o resultado terminal já escolhido.
7. **PAY-07** — IF a publicação na DLQ falhar THEN the SBUS SHALL manter estado recuperável e alertável até a DLQ ser confirmada, sem linha terminal silenciosa.
8. **PAY-08** — WHEN um retry possuir `not-before` futuro THEN the retry processor SHALL impedir processamento anterior ao instante, sem bloquear indefinidamente a partição de tráfego vivo.
9. **PAY-09** — IF Kafka, PostgreSQL, Redis ou Schema Registry estiver indisponível THEN each affected service SHALL aplicar timeout, retry, circuit/failure policy e readiness compatíveis com a garantia documentada.
10. **PAY-10** — The API waiter SHALL terminar por resultado, timeout, interrupção ou shutdown e SHALL remover MDC e registro local em todos os caminhos.
11. **PAY-11** — The durable status SHALL possuir transições válidas, retenção coerente com idempotência e consulta protegida por índice e autorização.
12. **PAY-12** — WHEN contratos Avro evoluírem THEN contract CI SHALL verificar compatibilidade definida antes da publicação.

**Independent Test**: Executar uma matriz de falhas com crash entre send/mark, duplicatas, payload divergente, dependências indisponíveis e concorrência multi-instância, provando estado final e ausência de perda silenciosa.

---

### P1: Correção e capacidade do async-to-sync Redis ⭐ MVP

**User Story**: Como consumidor do exemplo Redis, quero semântica coerente de fila, polling, backpressure e recuperação para que o serviço seja honesto sobre onde pode ser usado.

**Why P1**: A implementação atual possui riscos de trim de pendentes, consumidores não únicos, pool bloqueante e polling que retorna `UNKNOWN` para job ainda em processamento.

**Acceptance Criteria**:

1. **RED-01** — WHEN um job for aceito THEN the service SHALL persistir status consultável antes de enfileirar e SHALL retornar `PROCESSING` no polling até estado terminal ou expiração definida.
2. **RED-02** — IF o pool de waits estiver esgotado THEN the service SHALL respeitar timeout de aquisição finito e responder com backpressure explícito, sem bloquear além do orçamento HTTP.
3. **RED-03** — The stream retention policy SHALL impedir remoção de payload ainda pendente ou não consumido e SHALL alertar antes do limite seguro de backlog.
4. **RED-04** — WHEN múltiplas instâncias iniciarem workers THEN each consumer SHALL usar identidade única por instância e worker.
5. **RED-05** — IF Redis estiver indisponível no startup ou durante o loop THEN the worker SHALL reconectar com backoff e readiness SHALL permanecer down até haver capacidade de consumo.
6. **RED-06** — WHEN um worker liberar resultado THEN persistence, wakeup e TTL SHALL seguir operação atômica ou protocolo idempotente testado antes do ACK.
7. **RED-07** — IF uma mensagem for inválida ou exceder entregas THEN the worker SHALL gravar DLQ com motivo e ACK somente após confirmação da DLQ.
8. **RED-08** — The POST `/jobs` SHALL possuir idempotência, autenticação e limite de admissão habilitado no profile produtivo ou SHALL ser explicitamente classificado como exemplo não produtivo.

**Independent Test**: Rodar duas instâncias, saturar o pool, reiniciar Redis/workers, ultrapassar backlog e verificar polling, PEL, ACK e DLQ.

---

### P1: Capacidade e escalabilidade comprováveis ⭐ MVP

**User Story**: Como arquiteto, quero um modelo de capacidade e testes reproduzíveis para distinguir escalabilidade real de simples buffering.

**Why P1**: A meta de milhares por minuto precisa virar um gate com recursos, limites e critérios de sucesso observáveis.

**Acceptance Criteria**:

1. **CAP-01** — The architecture SHALL documentar a capacidade de cada estágio, taxa de chegada, taxa de serviço, backlog máximo, retenção e comportamento sob saturação.
2. **CAP-02** — WHEN a carga sustentada aprovada for aplicada no ambiente de referência THEN accepted requests SHALL apresentar zero perda silenciosa e taxa de erro técnico inferior a 0,1%.
3. **CAP-03** — WHEN a rajada aprovada exceder capacidade instantânea THEN the system SHALL aplicar `429`, `202` ou buffering limitado conforme orçamento, sem crescimento não limitado de memória, conexões, PEL ou outbox.
4. **CAP-04** — The load report SHALL registrar versão, hardware, recursos de container, configuração, massa, duração, percentis, throughput, erro, lag, backlog, GC, pools e banco.
5. **CAP-05** — The scaling test SHALL provar comportamento com pelo menos duas instâncias de API e duas de SBUS, incluindo ordenação por `requestId` e coordenação cross-instance.
6. **CAP-06** — IF a dependência Core suportar taxa menor que a entrada THEN the admission policy SHALL convergir para backlog limitado e recuperação mensurável, sem prometer SLO terminal impossível.
7. **CAP-07** — The performance gate SHALL falhar quando qualquer limiar aprovado não for atingido e SHALL preservar o relatório como evidência de CI ou execução controlada.

**Independent Test**: Executar cenários steady, spike, soak e dependency-slowdown no ambiente versionado e validar automaticamente os thresholds.

---

### P2: Feature control governável e previsível

**User Story**: Como consumidor da biblioteca de feature flags, quero semântica de consistência, validação e governança explícita para evitar rollout inseguro ou custo operacional inesperado.

**Why P2**: A biblioteca está isolada do pagamento, mas é transversal e afeta muitas aplicações.

**Acceptance Criteria**:

1. **FTR-01** — The feature definition SHALL validar campos por tipo, nomes, pesos, versões, limites e combinações inválidas antes de persistir ou ativar.
2. **FTR-02** — IF Redis ou pub/sub falhar THEN the resolver SHALL aplicar uma política documentada de last-known-good, baseline ou fail-closed com idade máxima observável.
3. **FTR-03** — WHEN uma flag for alterada ou removida THEN all instances SHALL convergir dentro do limite aprovado ou emitir alerta de degradação.
4. **FTR-04** — The admin operation SHALL usar autorização forte, concorrência otimista também para delete e auditoria com ator, before/after, timestamp e resultado.
5. **FTR-05** — The decision metrics SHALL limitar cardinalidade mesmo quando nomes e variantes vierem do admin e SHALL evitar PII em logs por padrão.
6. **FTR-06** — The library publication SHALL verificar POM, fontes, Javadoc, compatibilidade binária e uma aplicação consumidora usando apenas o artefato publicado.

**Independent Test**: Consumir a biblioteca publicada em fixture isolada e testar concorrência, Redis indisponível, cache envelhecido, propagação e cardinalidade.

---

### P2: Documentação, ADRs e semântica local para IA

**User Story**: Como pessoa ou agente de IA que altera uma fronteira, quero contexto local, fontes de verdade e gates precisos para trabalhar sem carregar ou corromper sistemas adjacentes.

**Why P2**: A autonomia futura depende de documentação localizada e verificável, não de um guia raiz que mistura tudo.

**Acceptance Criteria**:

1. **DOC-01** — The boundary README SHALL explicar propósito, quickstart, dependências externas, contratos publicados, operação e status de produção daquela fronteira apenas.
2. **DOC-02** — The boundary `AGENTS.md` SHALL definir mapa do código, fontes de verdade, invariantes, limites de ownership, ações proibidas e gates exatos daquela fronteira.
3. **DOC-03** — The boundary docs SHALL conter arquitetura, contratos, configuração, segurança, operação, observabilidade, testes, performance e índice de ADRs proporcionais ao tipo de projeto.
4. **DOC-04** — WHEN uma decisão irreversível, surpreendente e com trade-off for aprovada THEN the owning boundary SHALL registrar ADR numerado com contexto, decisão, alternativas, consequências e supersession.
5. **DOC-05** — IF um documento ou claim ficar sem correspondência no código/configuração THEN docs CI SHALL falhar ou o conteúdo SHALL ser removido/substituído antes da conclusão.
6. **DOC-06** — WHEN links, comandos, portas, tópicos, variáveis ou caminhos mudarem THEN docs validation SHALL detectar referências quebradas ou duplicadas.
7. **DOC-07** — The workspace-level documentation SHALL limitar-se ao mapa das fronteiras, workflow conjunto e sandbox, sem duplicar documentação pertencente a um projeto.

**Independent Test**: Entregar cada raiz isoladamente a um agente sem histórico e verificar que ele localiza fontes, executa gates e respeita os limites sem consultar documentação de outra fronteira.

---

### P2: Migração incremental, gates e higiene de entrega

**User Story**: Como mantenedor, quero uma sequência de migração reversível para reduzir blast radius e impedir uma reorganização big-bang sem evidência.

**Why P2**: Moves de build, contratos e infraestrutura atravessam todas as fronteiras e precisam de checkpoints.

**Acceptance Criteria**:

1. **MIG-01** — The migration SHALL executar por fases com uma fronteira ou infraestrutura coerente por vez e commit atômico por tarefa.
2. **MIG-02** — WHEN uma fronteira migrar THEN its old location SHALL permanecer somente até equivalência funcional, documental e operacional ser provada.
3. **MIG-03** — IF o baseline anterior já falhar THEN the migration SHALL registrar a falha como dívida preexistente e SHALL impedir atribuição incorreta ao move.
4. **MIG-04** — The per-boundary CI SHALL executar unit, integration, contract, build, packaging, image and Compose gates aplicáveis, com contagem esperada de testes.
5. **MIG-05** — The cross-boundary gate SHALL executar compatibilidade de artefatos e um smoke ponta a ponta sem reintroduzir build source-level compartilhado.
6. **MIG-06** — The repository SHALL possuir lint/format/static analysis, coverage policy, dependency update policy e `git diff --check` como gates documentados.
7. **MIG-07** — WHEN a documentação antiga for removida THEN the migration SHALL provar que toda informação ainda válida possui destino explícito.
8. **MIG-08** — IF qualquer gate de uma fase falhar THEN subsequent migration phases SHALL permanecer bloqueadas até correção ou decisão registrada.

**Independent Test**: Reexecutar a sequência em checkout limpo, interromper em cada fase e provar que o estado continua compilável, compreensível e reversível.

---

## Edge Cases

- **EDG-01** — IF um artefato publicado ainda não estiver disponível externamente durante desenvolvimento local THEN the workspace SHALL usar repositório Maven local ou composite build explícito sem mudar a declaração produtiva da dependência.
- **EDG-02** — IF uma migration Flyway já tiver sido aplicada THEN the migration SHALL permanecer imutável e qualquer correção SHALL usar nova versão append-only.
- **EDG-03** — IF um schema Avro incompatível for necessário THEN contract publication SHALL exigir nova versão major e estratégia de coexistência.
- **EDG-04** — IF um dashboard depender de métrica removida THEN observability validation SHALL detectar a referência antes de excluir a implementação antiga.
- **EDG-05** — IF Docker, Redis, Kafka ou PostgreSQL não estiver disponível no ambiente do agente THEN the affected integration gate SHALL ser reportado como não executado, nunca como aprovado.
- **EDG-06** — IF existirem mudanças locais não relacionadas ao iniciar uma tarefa THEN the executor SHALL preservá-las e SHALL interromper apenas quando houver sobreposição impossível de isolar.
- **EDG-07** — IF o core externo real não estiver disponível THEN performance certification SHALL usar um mock com capacidade e falhas determinísticas documentadas, sem apresentar o resultado como certificação do Core real.

---

## Implicit-Requirement Dimensions

| Dimension | Resolution |
| --------- | ---------- |
| Input validation & bounds | SEC-01, PAY-01/02, FTR-01, RED-02/03 e validação tipada de configurações. |
| Failure / partial-failure states | PAY-03/07/09, RED-05/06/07, MIG-03/08. |
| Idempotency / retry / duplicate handling | PAY-01/02/03/06/08, RED-06/08. |
| Auth boundaries & rate limits | SEC-01 a SEC-05, RED-08, CAP-03/06. |
| Concurrency / ordering | PAY-05/06, RED-04, FTR-04, CAP-05. |
| Data lifecycle / expiry | PAY-11, RED-01/03/06, SBX-05. |
| Observability | SBX-05, CAP-04/07, FTR-02/03/05, DOC-03. |
| External-dependency failure | PAY-09, RED-05, FTR-02, SBX-06. |
| State-transition integrity | PAY-03/04/05/07/11, RED-01/06/07. |

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| ORG-01 | Fronteiras prontas para repositórios | Execute | T2 ownership map complete; physical roots pending |
| ORG-02 | Fronteiras prontas para repositórios | Execute | T7 contracts, T19 Core mock, T24 SBUS, T31 API, T38 async Redis and T46 feature-control (library + feature-demo + pilot-app) builds standalone complete; remaining application roots pending |
| ORG-03 | Fronteiras prontas para repositórios | Execute | Core, SBUS, T37 API and T45 async Redis standalone build/release packages complete (own Dockerfile, app-only Compose, .env.example, README, docs, CI and AGENTS.md), the latter with no cross-boundary build context; feature-control pending |
| ORG-04 | Fronteiras prontas para repositórios | Execute | T7 publication, T11 fixture, T12 contracts docs, T46 feature-control standalone `maven-publish` and T52 feature-control consumer fixture (artifact-only GAV resolution verified against a real published jar, `exclusiveContent` reserving the group, missing-artifact negative check) complete; CI extraction pending |
| ORG-05 | Fronteiras prontas para repositórios | Execute | T6/T11 artifact-only flow plus T19 Core, T24 SBUS, T31 API GAV consumption, T46 feature-control (no cross-boundary `project()`; examples depend only on the boundary-local library) and T52 feature-control consumer fixture (same artifact-only guarantee, verified end to end) complete; remaining application migrations pending |
| ORG-06 | Fronteiras prontas para repositórios | Execute | T8 model and T9 bounded Avro adapter complete and framework-agnostic gate verified |
| ORG-07 | Fronteiras prontas para repositórios | Execute | T24 moved the SBUS rate limiter into its owner and T38 proved async Redis carries no Kafka/Postgres/common/contract dependency; remaining runtime concerns pending |
| ORG-08 | Fronteiras prontas para repositórios | Execute | T1 baseline and T54 reconciliation complete; T59 removed the legacy transitional roots entirely (no longer just excluded from the scan) after `equivalence.py verify` passed against the final standalone-only layout — `equivalence: PASS (409 entries)`, reconfirmed live by T60 |
| SBX-01 | Sandbox único | Execute | T13–T18 sandbox boundary complete with Compose/env/Make/smoke/docs/AGENTS |
| SBX-02 | Sandbox único | Execute | T13 minimal and T15 isolated observability/tools overlay complete; final app relocation pending |
| SBX-03 | Sandbox único | Execute | T13 named network and T22 Core app-only Compose adoption complete; remaining application Composes pending |
| SBX-04 | Sandbox único | Execute | T16 four profile combinations, synthetic 8085 collision and missing-variable validation complete |
| SBX-05 | Sandbox único | Execute | T15 common telemetry profiles complete; T58 activated the versioned manifest with real owner assets — `sandbox/observability/application-assets.json` declares owner/version/path for payment-api/payment-sbus/async-redis-service's alerts (mounted via `sandbox/compose.profiles.yml`, never copied — `sandbox/docs/observability.md`), `application-targets.json` registers real scrape targets for all four apps, proved live: sandbox Prometheus scrapes `payment-core-mock` (`up==1`) via this exact mechanism |
| SBX-06 | Sandbox único | Execute | T14 nine capacity probes, dependency-specific failure and recovery complete; application startup gates pending |
| SEC-01 | Segurança e operação | Execute | T3 hygiene, T25 SBUS, T32 API, T39 async Redis (ProductionAcceptanceGuard, RED-08) and T53 feature-control examples (`NonProductionExampleGuard`, unconditional refusal under `env=prod` — no valid production config exists for either example) production startup guards complete; remaining owner guards pending |
| SEC-02 | Segurança e operação | Execute | T32 API dev token issuer excluded from the prod bean graph/route (`@Requires(notEnv=prod)`, no HS256 secret in prod) and T53 feature-control (`feature-demo`/`pilot-app` never reach the bean graph at all under `prod` — a strict superset of route exclusion) complete; remaining demo surfaces pending |
| SEC-03 | Segurança e operação | Execute | T25 SBUS and T32 API require RSA JWKS, issuer, audience, expiration and strict clock policy; remaining applications pending |
| SEC-04 | Segurança e operação | Execute | T25 SBUS internal status, T32 API admin/business endpoints and T53 feature-control admin endpoint (`ROLE_ADMIN` via `intercept-url-map`, documented in `docs/security.md`, tested by `FeatureDemoFlowIT.adminRequiresAdminRole`; no internal endpoint in this boundary) require tested identity/role policy; remaining endpoints pending |
| SEC-05 | Segurança e operação | Execute | T25 SBUS and T32 API expose only liveness/readiness anonymously and protect aggregate health/metrics/unlisted endpoints; remaining apps pending |
| SEC-06 | Segurança e operação | Execute | T3 complete |
| SEC-07 | Segurança e operação | Execute | T17 sandbox plus T22 Core, T30 SBUS, T37 API and T45 async Redis images are tag+digest pinned with non-root/read-only runtime gates; the API image was built and inspected (10001:10001, liveness healthcheck, no runtime package added); the async Redis image build itself is env-limited here (Docker Hub egress blocked in this sandbox, confirmed with `docker pull hello-world`), structurally verified instead; remaining applications pending |
| SEC-08 | Segurança e operação | Execute | T30 SBUS, T37 API and T45 async Redis CI build the image, generate an SPDX SBOM and block HIGH/CRITICAL Trivy findings; T60 reproduced this tooling live against all 4 app images and recorded a real FAIL (25-31 findings per image). **Closed 2026-08-13** (`task_40100c4c`): coordinated Netty/Micronaut/Jackson/Kafka-clients/Avro/Postgres-driver bump across `payment-contracts` + the 4 app boundaries, full regression green on every boundary, images rebuilt and re-scanned — 0 Java/JVM findings remain on any image (down from 25-31 each). 3 findings per image remain (`libexpat`, `p11-kit`), all Alpine OS-package CVEs with no newer `eclipse-temurin:21-jre-alpine` digest published upstream yet (confirmed by a fresh pull) — outside this repository's control, a residual watch item rather than an open task. Full detail: `validation-evidence/2026-08-12-release-gate.md` §3 (original FAIL), `validation-evidence/2026-08-13-cve-remediation.md` (closure) |
| PAY-01 | Fluxo Kafka | Execute | T33 API atomically associates idempotency key + requestId + canonical fingerprint via a single Redis SET NX |
| PAY-02 | Fluxo Kafka | Execute | T33 API replays same key+fingerprint, returns deterministic 409 with zero publish on a divergent fingerprint |
| PAY-03 | Fluxo Kafka | Execute | T34 records the publish outcome on the idempotency reservation: a failed send marks PUBLISH_FAILED and a retry resumes the same requestId, a lapsed publish lease recovers a crashed attempt, and a replay without a stored status never reports PROCESSING |
| PAY-04 | Fluxo Kafka | Execute | T26 terminal state+outbox and T27 retry schedule-before-offset atomic persistence are proven |
| PAY-05 | Fluxo Kafka | Execute | T28 persisted token fences stale updates and a PostgreSQL session advisory lock prevents overlapping broker sends after lease reclaim; T55 proved live: a real published outbox row forced back to a stale IN_PROGRESS claim is reclaimed and republished by the running OutboxReaper (lease 1m + reaper-interval 30s), and a real `docker kill` mid-flight on sbus-1 is covered by sbus-2 via `FOR UPDATE SKIP LOCKED` |
| PAY-06 | Fluxo Kafka | Execute | T21 Core duplicate equivalence, T28 same-identity republish across the send/mark crash window and T36 API terminal-outcome preservation (identical and contradictory repeats leave the chosen result unchanged) complete; T55 proved live: forcing a real completed request's terminal-event outbox row back through the reaper's recovery path republishes it without altering the already-chosen `authorizationCode` |
| PAY-07 | Fluxo Kafka | Execute | T28 keeps broker/exhausted failures recoverable; unconfirmed count/age include active claims and alert continuously until DLQ_PUBLISHED; T55 proved live: a genuinely malformed message published straight to `payment.simulation.requested` (bypassing the API) reaches a new DLQ row and is confirmed DLQ_PUBLISHED, never silently dropped |
| PAY-08 | Fluxo Kafka | Execute | T27 due-based outbox publishes only when due, deduplicates crash redelivery and removes partition sleep; T55 proved live: a real future-dated (`next_attempt_at` +2m) outbox row stays PENDING/unclaimed while a concurrent live request completes in ~1s, unblocked |
| PAY-09 | Fluxo Kafka | Execute | T9 bounded Registry codec, T14 readiness diagnostics, T21 Core policy, T29 SBUS typed budgets, T35 API SBUS-fallback timeout/circuit/service identity and T36 API decode/apply retry-DLQ with no silent ack complete; T55 proved live against 3 of 4 dependencies (Kafka: 503 + idempotent recovery after ~2min producer delivery-timeout; PostgreSQL: API accepts via Kafka but settles only once Postgres returns, no false ack; Schema Registry: 503 fail-closed + auto-reregistration on recovery) — Redis (API idempotency-reservation path specifically, not the already-covered rate limiter) has a real gap: `RedisStatusStore.reserve()` leaks an unhandled 500 instead of failing closed, tracked as a follow-up (see T55 gate evidence) |
| PAY-10 | Fluxo Kafka | Execute | T35 ends the waiter on result, timeout, interruption and shutdown, clearing MDC and the local registration on every path, including publish failure and post-shutdown registration |
| PAY-11 | Fluxo Kafka | Execute | T26 V7 conditional transition makes the first terminal sticky; T29 startup guard aligns idempotency, durable state, published outbox, Kafka retention and redelivery windows; T33 API startup guard requires idempotency-ttl >= status-ttl |
| PAY-12 | Fluxo Kafka | Execute | T8 model and T10 FULL_TRANSITIVE compatibility gate complete |
| RED-01 | Async Redis | Execute | T39 persists a PROCESSING status before the enqueue and polling tells missing/processing/terminal/expired apart, with status-ttl >= result-ttl enforced at startup |
| RED-02 | Async Redis | Execute | T40 starts the wait budget before the borrow, caps acquisition at min(remaining budget, pool-max-wait), refuses startup on a pool with no capacity or no finite acquisition timeout, and answers a saturated pool with 202 plus X-Backpressure/Retry-After; it also fixed a connection leak from Lettuce ConnectionPoolSupport not wrapping the timed borrowObject overloads |
| RED-03 | Async Redis | Execute | T44 removes the inline `XADD MAXLEN ~` that could drop unconsumed payload under backlog pressure; `StreamRetentionMonitor` never auto-trims on any Redis version (the safe fallback design.md already recorded), alerts once backlog reaches a configurable safe budget, and reports ACKED-trim capability without ever invoking it — the pinned Lettuce 6.4.0.RELEASE has no typed ACKED support and no Redis >= 8.2 is available in this environment to verify it, so shipping that call would be untestable; see T44's SPEC_DEVIATION |
| RED-04 | Async Redis | Execute | T41 gives each worker a `<instance-id>-w<index>` consumer name derived per process, so two replicas never share a Redis consumer identity; XINFO CONSUMERS on a live Redis confirms 4 distinct consumers for 2 instances; a single ReclaimCoordinator turn (SET NX + owner-fenced renew/release) keeps only one worker scanning the PEL at a time |
| RED-05 | Async Redis | Execute | T41 reconnects the worker loop with capped exponential backoff on any connection loss (including startup), and WorkerReadiness/WorkerReadinessIndicator report DOWN until a worker has actually read from the group again, verified against a real Redis outage via a TCP gate |
| RED-06 | Async Redis | Execute | T42 releases result, status and wakeup as one atomic idempotent Lua EVAL (`ResultReleaser`); a redelivered release never duplicates the wakeup and never resurrects the status of a job that was never accepted, verified against a real Redis including under concurrent redeliveries |
| RED-07 | Async Redis | Execute | T43 fixes the max-deliveries off-by-one (`>=`, not `>`, so exactly `maxDeliveries` attempts occur, verified against real Redis PEL delivery counts), dead-letters a message with a missing/invalid jobId or amountCents before ever ACKing it (no more silent ACK of an invalid payload), and only ACKs a dead-lettered entry after its DLQ write is confirmed, leaving it recoverable in the PEL on a DLQ write failure |
| RED-08 | Async Redis | Execute | T39 adds X-API-Key AuthN on both job routes, SET NX fingerprint idempotency (replay/conflict, nothing enqueued twice) and a prod guard refusing startup without auth, idempotency and admission |
| CAP-01 | Capacidade | Execute | T29 types SBUS dependency budgets, retry attempts, readiness requirements and bounded recoverable states; T57's `load/capacity/manifest.yaml` documents arrival/service rate, backlog, retention and saturation behavior per stage for the full cross-boundary pipeline |
| CAP-02 | Capacidade | Execute | T57 ran steady (167 req/s×15min) live against `certified-target`: **FAIL** — technical error rate 4-44%, root-caused to a real `payment-api` gap (`ResponseCoordinator.complete()` / `RedisStatusStore.get()`, unpooled Redis connection hits Lettuce's default 60s command timeout under sustained load); evidence in `load/reports/20260811-185714-capacity-report.md`; not fixed in T57 (out of `load/`'s scope), tracked as `task_3801253b`. **Fixed** (follow-up, same session): `ResponseCoordinator.onMessage()` now dispatches to a virtual thread instead of running inline on Lettuce's PubSub event-loop thread (`ResponseCoordinator.java:126-128`), and `RedisClientTuning` bounds every Lettuce command to 2s instead of the 60s default (`payment-api/src/main/java/com/example/payments/api/redis/RedisClientTuning.java`). **Re-verified with a full, committed dated artifact** (the Verifier's Gap 1 finding — the earlier 90s ad hoc reproduction was never turned into a report): re-ran the full `certified-target/steady` scenario (167 req/s×15min, 150,301 requests) end to end — `load/reports/20260812-095631-capacity-report.md` — **technical error rate = 0.0000%** (was 4.1-44.4%), sub-criterion proven met with real evidence. The automated report's overall verdict was still FAIL on the "zero silent loss" sub-criterion (2/185 reconciliation samples); investigated and root-caused via a direct Postgres cross-check to be a false negative in the gate's own reconciliation methodology (status-ttl 15m exactly equals the scenario's own 15m duration, and `SbusStatusGateway`'s fallback circuit can be open under the same sustained load CAP-02 tests — both "lost" requests actually completed in ~1s), not a functional regression — full analysis in `load/reports/20260812-095631-cap02-addendum.md`. **Reconciliation methodology fixed** (`task_bca58451`): `load/capacity/reconcile.py` now cross-checks any request the HTTP path can't confirm terminal against `payment_sbus_message` directly (the durable store both the Redis status and its SBUS fallback ultimately read from or feed off of) before calling it lost — depends on no TTL, no circuit state, no timing coincidence. **Re-ran the exact same 167 req/s×15min scenario again to confirm**: `load/reports/20260812-230559-capacity-report.md` — 150,301 requests, technical error rate 0.0000%, reconciliation **426/426 terminal, 0 lost** (2 of the 426 needed the new durable-store cross-check — the same false-negative-prone pattern recurred under the same real load, and this time the gate absorbed it correctly instead of reporting FAIL). **Verdict: PASS** — CAP-02 fully certified with a clean automated gate result, both sub-criteria met, no manual override |
| CAP-03 | Capacidade | Execute | T37 API admission applies per-resource and per-tenant budgets with tested 202/429 plus Retry-After, and fails closed on a Redis outage onto limit/instances so a fleet never multiplies the approved burst; T40 bounds the async Redis waiter pool so a saturated service sheds the wait (202 + X-Backpressure) without dropping the job and never exceeds pool-max-total; T57's spike scenario (333 req/s×60s) confirmed 429/backpressure engages under both profiles without unbounded memory growth |
| CAP-04 | Capacidade | Execute | T57's `load/reports/20260811-185714-capacity-report.md` records version, container resources, config, duration, throughput, p50/p95/p99, status mix, Kafka-driven backlog (`sbus_outbox_pending`), DLQ age, GC pauses, heap, Hikari pool state, Redis memory and Postgres connections per scenario/profile |
| CAP-05 | Capacidade | Execute | T55 proved live against 2 real `payment-api` + 2 real `payment-sbus` instances (not Testcontainers): a concurrent duplicate Idempotency-Key across both API instances resolves to the same requestId, and 4 independent requests round-robined across the fleet never cross-talk or duplicate; T57 repeated the duplicate-key probe under sustained 167 req/s load in both profiles — still resolves to one requestId |
| CAP-06 | Capacidade | Execute | T20 deterministic Core outcomes/latency complete; T55 proved live: 15 requests against a Core forced to 4-6s latency (above the API's 3s wait-timeout) all return 200/202/422/429 (never 5xx) and all eventually complete, no silent loss under backlog; T57's `constrained-core` profile (Core capped at 50 msg/s by design, single-partition + fixed 20ms consume latency) ran steady/spike/soak/slowdown without crashing and drained its `slowdown`-induced backlog in under a second once Core capacity was restored, without promising an impossible terminal SLO |
| CAP-07 | Capacidade | Execute | T57's `generate_report.py` fails the gate (non-zero exit) when CAP-02's thresholds are breached — proved twice, independently: `generate_report.py selftest` deterministically confirms the fail path fires on a synthetic bad run, and the real `certified-target` run above failed it live; the report is preserved as versioned evidence in `load/reports/` |
| FTR-01 | Feature control | Execute | T47 bounds name/percentage/version/salt/labels and rejects invalid VARIANT/ALLOWLIST combinations in `FlagDefinition`'s single construction choke point (YAML, admin write and Redis deserialization all go through it) complete |
| FTR-02 | Feature control | Execute | T48 bounds last-known-good to `max-stale`, adds a `BASELINE`/`FAIL_CLOSED` fallback, an observable `ageOf` accessor and single-flight+jitter cache refresh in `RedisFlagSource`, verified against real Redis complete |
| FTR-03 | Feature control | Execute | T49 closes partial connections on subscribe failure (no leak), reconnects with capped backoff+jitter (`ReconnectBackoff`), and measures/alerts multi-instance convergence (`ConvergenceTracker`, verified with two real subscriber instances against real Redis) complete |
| FTR-04 | Feature control | Execute | T50 makes create/update/delete compare-and-set with the audit write in one Lua `EVAL` (`VersionedFlagStore`), requires a non-blank authenticated actor before any Redis call, and reads the version-less delete's CAS baseline from the authoritative store instead of the cached resolver (a real bug found and fixed during this task's own gate check) complete |
| FTR-05 | Feature control | Execute | T51 bounds decision metric tag cardinality per dimension (`CardinalityGuard`, flag/variant independently) and replaces the raw bucketing key in exposure logs with an irreversible hashed token (`SubjectHasher`), verified against a real meter registry and a real captured log line complete |
| FTR-06 | Feature control | Execute | T52 adds `consumer-fixture` (published-GAV-only, `exclusiveContent`-reserved) and `scripts/verify_api_surface.py` (`javap`-based public-API-surface diff against a committed baseline) so a breaking public API change fails the gate instead of shipping silently, verified end to end against a real published jar complete |
| DOC-01 | Docs, ADRs e IA | Execute | Contracts, sandbox, Core mock, T30 SBUS, T37 API, T45 async Redis and T53 feature-control READMEs complete; other boundaries pending |
| DOC-02 | Docs, ADRs e IA | Execute | Root, contracts, sandbox, Core mock, T30 SBUS, T37 API, T45 async Redis and T53 feature-control agent guides complete; other boundaries pending |
| DOC-03 | Docs, ADRs e IA | Execute | Contracts, sandbox, Core mock, T30 SBUS, T37 API, T45 async Redis and T53 feature-control proportional documentation complete (feature-control's set is intentionally narrower — no contracts/observability/performance docs or ops runbooks — for a library + two NON_PRODUCTION examples with no HTTP contract, no on-call surface and no dedicated capacity gate); T58 proved the observability claim live: payment-api's request/correlation/trace ids appear in real structured logs, but payment-api's causationId and payment-sbus's whole correlation-MDC wiring were real, confirmed gaps (`grep` found zero call sites), tracked as `task_89c681c8`. **Fixed** (follow-up, same session): `SimulationMessageHandler` now calls `Mdc.fromConsumer()`/`Mdc.clear()` around business processing (`payment-sbus/src/main/java/com/example/payments/sbus/kafka/SimulationMessageHandler.java`), and `ApiPaymentService`/`PaymentResponseConsumer` now put `causationId` into MDC. Re-ran T58's `verify.sh` live after rebuilding both images: 12/12 checks pass (previously 10/12) |
| DOC-04 | Docs, ADRs e IA | Execute | Contracts, sandbox, Core mock, SBUS, T37 API, T45 async Redis (ADR-0001, stream retention and atomic release) and T53 feature-control (ADR-0001, nonproduction example startup guard) accepted; other boundary decisions pending |
| DOC-05 | Docs, ADRs e IA | Execute | T5 manifest complete; T59 relocated/rewrote all 251 tracked sections (244 pending + earlier), `validate_docs.py` PASS (251 sections; links, commands, ports, variables, metrics, claims) against the final layout |
| DOC-06 | Docs, ADRs e IA | Execute | T5 docs validation and T16 ports/variables validation complete; T59 fixed `known_ports()`/`known_variables()` (were reading the deleted root `docker-compose.yml`, now scan each boundary's own `compose.yaml`/`.env.example`) and re-verified 0 broken links/ports/variables against the final relocated docs |
| DOC-07 | Docs, ADRs e IA | Execute | T2 root scope complete; T59 rewrote root `README.md`/`AGENTS.md` to the final boundary map with no "transitional"/"planned" framing, `check_root_governance.py` PASS |
| MIG-01 | Migração e gates | Execute | T59 executed the final phase (doc migration, dashboard relocation, CI/dependabot rewrite, legacy deletion) as one atomic commit after `scripts/verify-workspace.sh` PASS |
| MIG-02 | Migração e gates | Execute | T1 baseline, T24 SBUS destination/checksum/full equivalence, T31 API destination/full-gate equivalence, T38 async Redis in-place extraction (6 baseline tests preserved under both roots) and T46 feature-control regroup (31 baseline tests preserved, both the transitional workspace root and the new standalone root build and test the same files) complete; T59 deleted the old locations (`common`, `api-service`, `sbus-service`, `core-mock`, root `build.gradle`/`settings.gradle`/`Dockerfile`/`docker-compose.yml`/`Makefile`/`gradlew`) only after `equivalence.py verify` PASS at 409 entries against the standalone roots alone |
| MIG-03 | Migração e gates | Execute | T1 complete; T60 ran the full Quick+Full test gate live for all 6 Java boundaries against the final post-T59 layout — all 6 green (`validation-evidence/2026-08-12-release-gate.md` §1) |
| MIG-04 | Migração e gates | Execute | T4 transitional matrix and T54 real build/packaging/Compose gates for all 4 consumer boundaries (`payment-api`, `payment-sbus`, `payment-core-mock`, `async-redis-service`, each built and run standalone against published artifacts) complete; per-boundary GitHub Actions extraction into separate repositories pending a later initiative (AD-004) |
| MIG-05 | Migração e gates | Execute | T54 complete: `scripts/e2e/check_no_composite_build.py` proves no consumer boundary uses `includeBuild`/`project(...)`/sibling source (structural, against the real 4 boundaries), `scripts/artifacts/verify-artifact-only.sh` proves the GAV-resolution mechanism (published resolves, missing fails), and two real end-to-end smokes (`scripts/smoke.sh`, `scripts/e2e/async_redis_smoke.sh`) pass against Docker images built from published local Maven repositories, composed independently on the sandbox network; T59 re-pointed `verify-artifact-only.sh` at `feature-control`'s own wrapper (borrowed as a generic `-p` launcher for the fixture) after the root wrapper was deleted, re-verified live |
| MIG-06 | Migração e gates | Execute | T4 policy recorded; T60 closed the supply-chain verification loop — SBOM+scan tooling confirmed working live against all 4 app images (see SEC-08); the images themselves don't yet pass it, tracked as `task_40100c4c` rather than blocking T60's own evidence-gathering scope |
| MIG-07 | Migração e gates | Execute | T5 section destinations complete; T59 proved removal: `build_relocation_manifest.py`/`validate_docs.py` treat "every ROUTES source gone" as terminal state and verify all 251 recorded sections are `MIGRATED`, not silently stale |
| MIG-08 | Migração e gates | Execute | T59 is the first phase whose gate (`scripts/verify-workspace.sh`) would have blocked the deletion commit on failure; it ran clean (equivalence, no-composite-build, artifact-only-consumer, both e2e smokes, both failure matrices at their floor, hygiene) before the commit was made. T60 (the final release gate) recorded a real FAIL — SEC-08's SBOM/Trivy gate — rather than promoting it to PASS, proving the blocking behavior this requirement demands actually holds at the last phase, not just mid-migration |
| EDG-01 | Edge cases | Execute | T6 complete |
| EDG-02 | Edge cases | Execute | T26 added append-only V7 and preserved checksum evidence for V1–V7 |
| EDG-03 | Edge cases | Execute | T10 incompatible evolution requires major, new artifact/topic and coexistence |
| EDG-04 | Edge cases | Execute | T5 dashboard metric validation complete; T58 proved it end-to-end with a real injected fixture (not just a synthetic string unit test) and fixed a real pre-existing blind spot in `executable_corpus()`'s glob (missed `feature-control/library` and `feature-control/examples/*`'s nested `src/main/java`, which had silently let `feature_decisions_total` go unverified since before this task) |
| EDG-05 | Edge cases | Execute | T4 PASS/FAIL/NOT_RUN classification complete; T60 exercised it for real at the release gate — the SBOM/Trivy findings are recorded as FAIL, not silently promoted to PASS, and every gate's evidence names its actual command/exit code/count rather than an assumed result |
| EDG-06 | Edge cases | Execute | T1 complete |
| EDG-07 | Edge cases | Execute | T20 deterministic requestId/seed behavior and T23 NON_PRODUCTION profiles/performance limits complete; T57's `manifest.yaml` documents Core-mock's deterministic seed/latency/decline config per profile and the report explicitly labels `certified-target`/`constrained-core` as Core-mock-driven, never presented as Core-real certification |

**Coverage:** 77 requirements total, 77 mapped to tasks, 0 pending design.

---

## Success Criteria

- [ ] Todas as fronteiras aprovadas passam seus gates a partir da própria raiz e não possuem dependência Gradle por source path em outra fronteira.
- [ ] O sandbox sobe sem conflito de portas, sem código de aplicação e com smoke de todas as dependências.
- [ ] Nenhum P0/P1 de segurança, perda de dados, idempotência ou disponibilidade permanece sem correção ou bloqueio explícito de produção.
- [ ] O fluxo Kafka e o fluxo Redis possuem testes de falha, duplicata, restart e multi-instância alinhados aos critérios desta especificação.
- [ ] A meta de carga aprovada possui relatório reproduzível e gate automático; qualquer limitação do Core aparece como capacidade, não como promessa.
- [ ] Cada fronteira possui README, docs, ADRs e `AGENTS.md` locais sem links, comandos ou claims obsoletos.
- [ ] A documentação raiz deixa de misturar produtos e passa a ser somente um mapa do workspace e do sandbox.
