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
| ORG-02 | Fronteiras prontas para repositórios | Execute | T7 contracts and T19 Core mock builds standalone complete; remaining application roots pending |
| ORG-03 | Fronteiras prontas para repositórios | Execute | T19/T22 Core mock standalone build and isolated container package complete; its docs/CI and remaining applications pending |
| ORG-04 | Fronteiras prontas para repositórios | Execute | T7 publication, T11 fixture and T12 contracts docs complete; CI extraction pending |
| ORG-05 | Fronteiras prontas para repositórios | Execute | T6/T11 artifact-only flow and T19 Core mock GAV consumption complete; remaining application migrations pending |
| ORG-06 | Fronteiras prontas para repositórios | Execute | T8 model and T9 bounded Avro adapter complete and framework-agnostic gate verified |
| ORG-07 | Fronteiras prontas para repositórios | Tasks | In Tasks |
| ORG-08 | Fronteiras prontas para repositórios | Execute | T1 complete; final equivalence pending |
| SBX-01 | Sandbox único | Execute | T13–T18 sandbox boundary complete with Compose/env/Make/smoke/docs/AGENTS |
| SBX-02 | Sandbox único | Execute | T13 minimal and T15 isolated observability/tools overlay complete; final app relocation pending |
| SBX-03 | Sandbox único | Execute | T13 named network and T22 Core app-only Compose adoption complete; remaining application Composes pending |
| SBX-04 | Sandbox único | Execute | T16 four profile combinations, synthetic 8085 collision and missing-variable validation complete |
| SBX-05 | Sandbox único | Execute | T15 common telemetry profiles and empty versioned application-asset manifest complete; owner assets pending |
| SBX-06 | Sandbox único | Execute | T14 nine capacity probes, dependency-specific failure and recovery complete; application startup gates pending |
| SEC-01 | Segurança e operação | Execute | T3 config hygiene complete; owner startup guards pending |
| SEC-02 | Segurança e operação | Tasks | In Tasks |
| SEC-03 | Segurança e operação | Tasks | In Tasks |
| SEC-04 | Segurança e operação | Tasks | In Tasks |
| SEC-05 | Segurança e operação | Tasks | In Tasks |
| SEC-06 | Segurança e operação | Execute | T3 complete |
| SEC-07 | Segurança e operação | Execute | T17 sandbox and T22 Core mock images tag+digest pinned; Core runtime non-root gate complete, remaining applications pending |
| SEC-08 | Segurança e operação | Tasks | In Tasks |
| PAY-01 | Fluxo Kafka | Tasks | In Tasks |
| PAY-02 | Fluxo Kafka | Tasks | In Tasks |
| PAY-03 | Fluxo Kafka | Tasks | In Tasks |
| PAY-04 | Fluxo Kafka | Tasks | In Tasks |
| PAY-05 | Fluxo Kafka | Tasks | In Tasks |
| PAY-06 | Fluxo Kafka | Execute | T21 Core duplicate payload equivalence complete; SBUS/API downstream idempotency and crash-window gates pending |
| PAY-07 | Fluxo Kafka | Tasks | In Tasks |
| PAY-08 | Fluxo Kafka | Tasks | In Tasks |
| PAY-09 | Fluxo Kafka | Execute | T9 bounded Registry codec, T14 readiness diagnostics and T21 Core uncommitted failure/Registry policy complete; remaining service policies pending |
| PAY-10 | Fluxo Kafka | Tasks | In Tasks |
| PAY-11 | Fluxo Kafka | Tasks | In Tasks |
| PAY-12 | Fluxo Kafka | Execute | T8 model and T10 FULL_TRANSITIVE compatibility gate complete |
| RED-01 | Async Redis | Tasks | In Tasks |
| RED-02 | Async Redis | Tasks | In Tasks |
| RED-03 | Async Redis | Tasks | In Tasks |
| RED-04 | Async Redis | Tasks | In Tasks |
| RED-05 | Async Redis | Tasks | In Tasks |
| RED-06 | Async Redis | Tasks | In Tasks |
| RED-07 | Async Redis | Tasks | In Tasks |
| RED-08 | Async Redis | Tasks | In Tasks |
| CAP-01 | Capacidade | Tasks | In Tasks |
| CAP-02 | Capacidade | Tasks | In Tasks |
| CAP-03 | Capacidade | Tasks | In Tasks |
| CAP-04 | Capacidade | Tasks | In Tasks |
| CAP-05 | Capacidade | Tasks | In Tasks |
| CAP-06 | Capacidade | Execute | T20 deterministic Core outcomes/latency complete; bounded admission and slowdown certification pending |
| CAP-07 | Capacidade | Tasks | In Tasks |
| FTR-01 | Feature control | Tasks | In Tasks |
| FTR-02 | Feature control | Tasks | In Tasks |
| FTR-03 | Feature control | Tasks | In Tasks |
| FTR-04 | Feature control | Tasks | In Tasks |
| FTR-05 | Feature control | Tasks | In Tasks |
| FTR-06 | Feature control | Tasks | In Tasks |
| DOC-01 | Docs, ADRs e IA | Execute | T12 contracts and T18 sandbox README complete; other boundaries pending |
| DOC-02 | Docs, ADRs e IA | Execute | T2 root, T12 contracts and T18 sandbox agent guides complete; other boundaries pending |
| DOC-03 | Docs, ADRs e IA | Execute | T12 contracts and T18 sandbox proportional documentation complete; other boundaries pending |
| DOC-04 | Docs, ADRs e IA | Execute | Contracts and sandbox ADR-0001 accepted; other boundary decisions pending |
| DOC-05 | Docs, ADRs e IA | Execute | T5 manifest complete; relocation pending |
| DOC-06 | Docs, ADRs e IA | Execute | T5 docs validation and T16 ports/variables validation complete; final relocation links pending |
| DOC-07 | Docs, ADRs e IA | Execute | T2 root scope complete; legacy relocation pending |
| MIG-01 | Migração e gates | Tasks | In Tasks |
| MIG-02 | Migração e gates | Execute | T1 baseline complete; relocation gates pending |
| MIG-03 | Migração e gates | Execute | T1 complete |
| MIG-04 | Migração e gates | Execute | T4 transitional matrix complete; standalone workflows pending |
| MIG-05 | Migração e gates | Tasks | In Tasks |
| MIG-06 | Migração e gates | Execute | T4 policy recorded; coverage and supply-chain closure pending |
| MIG-07 | Migração e gates | Execute | T5 section destinations complete; removal proof pending |
| MIG-08 | Migração e gates | Tasks | In Tasks |
| EDG-01 | Edge cases | Execute | T6 complete |
| EDG-02 | Edge cases | Tasks | In Tasks |
| EDG-03 | Edge cases | Execute | T10 incompatible evolution requires major, new artifact/topic and coexistence |
| EDG-04 | Edge cases | Execute | T5 dashboard metric validation complete |
| EDG-05 | Edge cases | Execute | T4 PASS/FAIL/NOT_RUN classification complete |
| EDG-06 | Edge cases | Execute | T1 complete |
| EDG-07 | Edge cases | Execute | T20 deterministic requestId/seed behavior and validated profiles complete; NON_PRODUCTION docs and performance evidence pending |

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
