# Política de tecnologia

Catálogo de tecnologias adotadas no workspace: o que é, por que o workspace usa, e qual fronteira é dona da decisão. Configuração e caminho de código específicos pertencem ao `configuration.md`/`architecture.md` de cada fronteira; aqui fica só o racional que atravessa fronteiras ou que justifica a escolha compartilhada.

## Linguagem e runtime

### Java 21 (rumo a 25) com Virtual Threads

Virtual threads (Project Loom) são threads leves gerenciadas pela JVM: bloquear em I/O custa quase nada, porque a carrier thread é liberada. O workspace usa isso porque `payment-api` bloqueia aguardando o evento de resultado; com virtual threads, milhares de requisições podem esperar simultaneamente sem esgotar threads de plataforma. O alvo de versão é centralizado numa única propriedade de build, compartilhada entre fronteiras. Virtual threads tornam a espera barata, mas não limitam carga no Core: quem faz isso é Kafka, outbox e rate limiter (ver [Contratos de resiliência](resilience-contracts.md)).

### Micronaut (framework)

Framework JVM com injeção de dependência em tempo de compilação (sem reflexão pesada), startup rápido, e módulos para HTTP, Kafka, Data, Redis, métricas e tracing. O workspace usa porque integra todos os pilares com pouca cerimônia e suporte nativo a virtual threads. Cada fronteira aplicação (`payment-api`, `payment-sbus`, `payment-core-mock`, `feature-control` e seus exemplos) configura seu próprio `application.yml`.

## Build

### Gradle

Ferramenta de build. Cada raiz standalone compila e testa sem depender de outra raiz; dependências entre fronteiras usam coordenadas versionadas (GAV), não referência direta de módulo. Um composite build cross-boundary é opcional e explícito; gates de release desabilitam substitution e consomem o artefato publicado (ver [AGENTS.md](../AGENTS.md)).

### Gradle maven-publish

Plugin que publica uma biblioteca como artefato Maven (jar, sources, javadoc, POM). Permite que aplicações consumidoras adotem `feature-control` por versão (SemVer), não por código-fonte copiado: governança e evolução controladas.

## Contrato e mensageria

### Apache Kafka (KRaft)

Plataforma de streaming por tópicos particionados; KRaft é o modo sem ZooKeeper. O workspace usa Kafka como buffer entre `payment-api` e `payment-sbus`: absorve rajada, dá backpressure natural, desacopla cadências, entrega at-least-once. Particionamento por `requestId` garante ordenação por simulação.

### Apache Avro

Formato de serialização binário com schema. Dá contrato forte aos eventos no Kafka (em vez de JSON solto) e base para versionamento. Valores monetários são strings decimais para evitar problemas de ponto flutuante; `occurredAt` é epoch millis. Schemas e mapeamento POJO/Avro pertencem a `payment-contracts`.

### Apicurio Schema Registry

Registro de schemas (Avro/Protobuf/JSON Schema) com API compatível com a do Confluent. O workspace usa Apicurio porque os serdes do Confluent não estão disponíveis no Maven Central; o serde Apicurio está. O serializador desabilita headers e embute o schema id no payload, tornando os bytes auto-descritivos: essencial para a outbox do `payment-sbus` republicar eventos intactos.

### Redis Streams + Consumer Groups

Estrutura de log append-only do Redis, com grupos de consumo e lista de pendências. É a fila durável do exemplo async-to-sync sem Kafka, no `async-redis-service`: dá at-least-once, reprocessamento de mensagens travadas e dead-letter sem broker dedicado.

### Redis Pub/Sub

Mensageria fire-and-forget do Redis. Propaga mudanças de estado em milissegundos e acorda waiters entre instâncias. Em `payment-api`, acorda quem está esperando um resultado; em `feature-control`, propaga flips de feature sem esperar o TTL do cache.

## Dados

### Redis (cliente Lettuce)

Armazenamento chave-valor em memória, com pub/sub. Usado por `payment-api` para status/resultado temporário por `requestId`, mapa de idempotência, e coordenação de waiters entre instâncias. A conexão é obtida lazy: a aplicação sobe mesmo com Redis fora do ar.

### Lettuce + commons-pool2 (pool de conexões)

Comandos bloqueantes (como `BRPOP`) monopolizam a conexão Redis; um pool sobre commons-pool2 limita e reutiliza conexões sob concorrência, evitando abrir uma conexão nova por request. Usado no `async-redis-service`.

### PostgreSQL

Banco relacional. Fonte durável do estado da simulação, da outbox e dos registros de idempotência em `payment-sbus`: é o que garante que o resultado não se perde. `stringtype=unspecified` na URL deixa o driver fazer cast automático de string para `jsonb`.

### Micronaut Data JDBC

Camada de persistência leve, com repositórios gerados em compilação, sem ORM pesado. Usado para CRUD tipado e queries nativas do padrão outbox, incluindo `FOR UPDATE SKIP LOCKED` para permitir múltiplos publicadores concorrentes.

### Flyway (migrations)

Versionamento de schema de banco via scripts SQL ordenados, aplicados automaticamente no boot de `payment-sbus`. Garante schema reproduzível e versionado.

### Outbox Pattern

Técnica para resolver dual-write: grava o evento a publicar numa tabela na mesma transação do estado; um publicador assíncrono envia ao broker depois, fora da transação, via claim/lease (`PENDING`→`IN_PROGRESS`→`PUBLISHED`/`FAILED`). Mantém o Core agnóstico do mecanismo de publicação. Detalhe completo em [Contratos de resiliência](resilience-contracts.md) e no `architecture.md` de `payment-sbus`.

## Rate limiting e segurança

### Rate limiting distribuído (Redis)

Limitador de taxa global entre instâncias, sobre Redis (janela fixa via script atômico), com fallback local se o Redis cair. Protege o Core (limita publicações do comando ao Core) e a admissão da API (rejeita rajada com `429`). Um limiter só por instância permitiria N vezes o limite no agregado do workspace.

### Segurança (API key → JWT/OAuth2)

Autenticação dos endpoints de negócio de `payment-api` via header de API key, como exemplo funcional de um PoC. Caminho de produção evolui para JWT/OAuth2 mais mTLS entre fronteiras, ver [Evidências de produção](production-evidence.md).

### Micronaut Security (JWT / JWKS)

Módulo de autenticação/autorização que valida JWT e mapeia claims para autenticação da aplicação. Importante para `feature-control` identificar usuário e grupos no controle de features (allowlist, chave por usuário) e proteger rotas administrativas com papel de admin. Desenvolvimento usa segredo compartilhado (HS256); produção usa chaves rotacionáveis via JWKS (RS256).

## Observabilidade

### OpenTelemetry (OTel)

Padrão de instrumentação para traces e métricas; propaga contexto via header W3C `traceparent`. Usado para rastrear uma simulação ponta a ponta atravessando HTTP, Kafka e as três fronteiras do fluxo principal, inclusive através do broker.

### OpenTelemetry Collector

Processo que recebe telemetria via OTLP e exporta para backends. Ponto único de coleta cross-boundary: encaminha traces ao Jaeger e expõe métricas ao Prometheus.

### Jaeger

Backend e UI de tracing distribuído, usado para visualizar o trace de uma simulação atravessando os serviços.

### Prometheus

Banco de séries temporais que faz scrape de métricas e avalia alertas. Coleta métricas de todas as fronteiras aplicação e dispara alertas de outbox, DLQ e latência.

### Grafana

Visualização de métricas em dashboards, com datasource Prometheus provisionado automaticamente. Painéis cobrem API, SBUS, outbox, Kafka, Redis e PostgreSQL.

### Logback + JSON estruturado

Framework de logging; com encoder logstash, os logs saem em JSON. Cada linha carrega `requestId`, `correlationId`, `causationId`, `traceId`, tipo de evento, tópico, partição, offset e status via contexto de log, tornando logs correlacionáveis entre fronteiras.

### Micrometer

Fachada de métricas exportada ao Prometheus. Torna o rollout de features observável e o backlog do fluxo async visível: base para decidir promover ou reverter.

## Infraestrutura local e testes

### Docker Compose

Orquestra os contêineres locais. Somente a fronteira `sandbox` cria infraestrutura local compartilhada (Kafka, Redis, PostgreSQL, Schema Registry); composes de aplicação conectam à rede externa do sandbox em vez de duplicar infraestrutura (ver [AGENTS.md](../AGENTS.md)).

### Testcontainers

Sobe dependências reais (Kafka, Postgres, Redis, Apicurio) em contêineres durante os testes de integração de cada fronteira. Garante testes fiéis ao runtime, sem mocks de infraestrutura.

### k6

Ferramenta de teste de carga em JavaScript. Valida o comportamento do fluxo principal sob rajada: respostas 200/202, 429 de rate limit, comportamento de virtual threads. Detalhe em [Política de testes](testing-policy.md).

## Ver também
- [Contratos de resiliência](resilience-contracts.md) · [Ownership de dados](data-ownership.md) · [Política de testes](testing-policy.md)
