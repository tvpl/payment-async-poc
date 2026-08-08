# Guia do repositório para agentes

Este arquivo é o ponto de entrada para alterações assistidas por IA. Ele descreve o mapa mínimo do codebase, as fontes de verdade e os gates obrigatórios. Leia também o documento específico do módulo que será alterado.

## Objetivo do sistema

Este repositório é uma PoC em Java 21 e Micronaut para três capacidades relacionadas, mas arquiteturalmente distintas:

1. simulação de pagamento síncrona sobre processamento assíncrono com Kafka;
2. controle de features compartilhado entre aplicações;
3. exemplo de async-to-sync baseado apenas em Redis.

Não trate essas capacidades como um único fluxo. O caminho crítico de pagamento usa `api-service`, `sbus-service`, `core-mock` e `common`. A biblioteca `feature-control` é transversal. `async-redis-service` é uma alternativa autossuficiente e não participa do fluxo Kafka.

## Fontes de verdade

Quando houver divergência, use esta ordem:

1. código e testes do módulo;
2. configuração executável: `settings.gradle`, `build.gradle`, `gradle.properties`, `application*.yml`, `docker-compose.yml` e workflows;
3. migrations em `sbus-service/src/main/resources/db/migration/`;
4. schemas Avro e constantes em `common/src/main/`;
5. documentação em `docs/` e `README.md`.

Não corrija código para fazê-lo coincidir com um texto desatualizado. Confirme a intenção e atualize a fonte incorreta.

## Mapa dos módulos

Os oito módulos abaixo são declarados em `settings.gradle`.

| Módulo | Papel | Pontos de entrada | Leitura principal |
| ------ | ----- | ----------------- | ----------------- |
| `common` | Contratos compartilhados do fluxo Kafka: envelope, modelos, schemas Avro, serde, mapper, tópicos e headers | `common/src/main/avro/`, `common/src/main/java/com/example/payments/common/` | `docs/08-eventos-e-contratos.md` |
| `api-service` | HTTP, autenticação, rate limit, idempotência em Redis, publicação Kafka e espera curta pelo resultado | `PaymentSimulationController`, `ApiPaymentService`, `ResponseCoordinator`, `RedisStatusStore` | `docs/05-api-service.md` |
| `sbus-service` | Processamento durável, idempotência, Postgres, transactional outbox, retry/DLQ e proteção do Core | `SimulationMessageHandler`, `PaymentSimulationService`, `PaymentPersistenceService`, `OutboxDispatcher` | `docs/06-sbus-service.md` |
| `core-mock` | Simulação do Core externo; consome comando e publica resposta | `CoreSimulationConsumer` | `docs/07-core-mock.md` |
| `feature-control` | Biblioteca de toggle, rollout percentual, allowlist JWT, resolução estática/Redis e auditoria | `FeatureResolver`, `CompositeFlagSource`, `FeatureGateInterceptor` | `docs/16-feature-control-lib.md` |
| `feature-demo` | Aplicação executável que demonstra os cenários da biblioteca | `FeatureDemoController`, `FeatureAdminController` | `docs/16-feature-control-lib.md`, `docs/18-operacao-features.md` |
| `async-redis-service` | Exemplo autossuficiente de async-to-sync com Redis Streams, consumer group, BRPOP e DLQ | `AsyncJobController`, `JobQueue`, `JobWorker` | `docs/17-async-sync-redis.md` |
| `pilot-app` | Consumidor mínimo de referência para adoção de `feature-control` | `PilotController` | `docs/19-adocao.md` |

Infraestrutura local e observabilidade ficam em `docker-compose.yml`, `observability/` e `deploy/`. Scripts operacionais ficam em `Makefile`, `scripts/` e `load/`.

## Fluxos que não podem ser confundidos

### Pagamento via Kafka

```text
HTTP -> api-service -> Kafka(requested) -> sbus-service
     -> Postgres + outbox -> Kafka(core command) -> core-mock
     -> Kafka(core response) -> sbus-service -> Postgres + outbox
     -> Kafka(completed/failed) -> api-service -> Redis -> HTTP/polling
```

O `requestId` é a chave Kafka e a identidade da simulação. A API pode responder com resultado terminal dentro da janela de espera ou com `202` para polling. Redis coordena instâncias da API; Postgres no SBUS mantém o resultado durável.

### Feature control

`feature-control` é uma biblioteca, não um serviço central. Cada aplicação consumidora resolve flags localmente a partir da configuração estática e, quando habilitado, do Redis. `feature-demo` demonstra as variantes; `pilot-app` mostra a adoção mínima; `api-service` usa a biblioteca na rota v0 e no roteamento de tópico.

### Async-to-sync via Redis

`async-redis-service` publica jobs em Redis Streams, processa por consumer group e acorda a requisição com uma lista por job via BRPOP. O resultado durável tem TTL e suporta polling. Esse serviço não usa Kafka, Postgres, `common` ou o outbox do SBUS.

## Invariantes arquiteturais

### Eventos e contratos

- Edite schemas em `common/src/main/avro/`; classes Avro geradas em `build/` não são fonte e não devem ser editadas.
- Mantenha schema, `AvroMapper`, `EventTypes`, `Topics`, headers Kafka e exemplos em `docs/events/` coerentes.
- Mensagens Kafka usam Avro binário com schema id embutido. HTTP e Redis usam JSON.
- A outbox armazena os bytes Avro já serializados e os republica sem reconstruir o payload.
- Se adicionar ou renomear tópico, atualize `Topics.java`, producers/consumers, `docker-compose.yml` (`kafka-init`), observabilidade, testes e documentação.
- Preserve `requestId`, `correlationId`, `causationId`, `eventType`, `eventVersion` e propagação de trace conforme o contrato existente.

### Persistência, idempotência e outbox

- Migrations Flyway são append-only. Nunca reescreva uma migration aplicada; adicione a próxima `V<N>__descricao.sql`.
- Mudança de estado do SBUS e criação da linha de outbox pertencem à mesma transação em `PaymentPersistenceService`.
- Serialização Avro e publicação Kafka não devem ocorrer dentro dessa transação de banco.
- O dispatcher usa claim/lease, publica fora da transação e depois marca o resultado. Preserve reaper, backoff, limite de tentativas e DLQ.
- Não remova unicidade de `request_id` ou `idempotency_key` sem uma decisão explícita de arquitetura.
- Não troque a coordenação Redis da API por memória local: ela precisa funcionar com múltiplas instâncias.

### Concorrência e falhas

- A espera HTTP ocorre em virtual thread e sempre termina por resultado, timeout, interrupção ou shutdown.
- Registre o waiter antes de publicar e preserve a leitura após registro que cobre respostas muito rápidas.
- Kafka mantém ordenação por simulação porque as mensagens usam `requestId` como chave.
- Retries transitórios usam tópicos dedicados; poison messages terminam na DLQ.
- No serviço Redis, jobs só são confirmados após liberar o resultado; falhas permanecem no PEL para reclaim ou DLQ.
- Rate limits da API, do SBUS e do serviço Redis protegem recursos diferentes. Não os consolide sem analisar o fluxo completo.

### Feature flags e segurança

- Preserve bucketing determinístico e sticky. Não use aleatoriedade por requisição.
- O contexto de usuário/grupo vem da autenticação JWT; não aceite identidade arbitrária do payload quando a rota é protegida.
- Configurações `application-prod.yml` usam JWKS/issuer. Segredos locais são apenas defaults de desenvolvimento.
- Nunca registre chaves, tokens, senhas ou conteúdo de `.env` em docs, testes, logs ou commits.

## Como fazer mudanças

1. Leia este arquivo, o `build.gradle` do módulo, seu `application*.yml`, testes vizinhos e o documento listado na tabela.
2. Localize o comportamento existente com `rg`. `ast-grep` não é requisito do projeto.
3. Defina o menor conjunto de arquivos e o gate antes de editar.
4. Derive testes do comportamento requerido. Não copie a implementação para dentro do teste.
5. Faça mudanças cirúrgicas. Não refatore módulos adjacentes no mesmo commit.
6. Atualize a documentação quando mudar contrato, configuração, operação, migration, endpoint, tópico, métrica ou garantia arquitetural.
7. Execute primeiro o gate do módulo e depois o gate de repositório proporcional ao risco.

## Gates de validação

### Sem Docker

```bash
# módulo alterado
./gradlew :<modulo>:test

# todos os testes unitários; classes *IT são excluídas por padrão
./gradlew test

# compilação, testes unitários, jars e demais checks Gradle
./gradlew build

# validação estrutural do Compose
docker compose config -q
```

Use os nomes exatos de módulo da tabela. Para `feature-control`, valide também o artefato quando tocar publicação ou API pública:

```bash
./gradlew :feature-control:publishMavenPublicationToLocalBuildRepository
```

### Com integração

Testes `*IT` só entram com `-PwithIT`. A maioria usa Testcontainers e precisa de Docker. Os ITs de Redis usados no CI também aceitam `REDIS_TEST_URI` apontando para um Redis externo.

```bash
# suíte completa de integração, com Docker disponível
./gradlew test -PwithIT

# mesmo recorte Redis usado pelo CI, com Redis em localhost:6379
REDIS_TEST_URI=redis://localhost:6379 \
  ./gradlew :async-redis-service:test :feature-demo:test :pilot-app:test -PwithIT

# demonstração ponta a ponta local
make demo
```

Não execute `make clean` como gate: ele remove volumes locais. Para operação e troubleshooting, siga `docs/12-execucao-e-operacao.md`.

## Convenções de código e testes

- Java 21, Gradle multi-project e Micronaut 4. A versão alvo fica em `gradle.properties`.
- Pacotes do pagamento usam `com.example.payments`; módulos de plataforma usam `com.example.platform`.
- Testes unitários terminam em `UnitTest` ou `Test`; integrações dependentes de infraestrutura terminam em `IT`.
- Use JUnit 5. Integrações existentes usam Micronaut Test, Testcontainers e Awaitility conforme o módulo.
- Siga injeção por construtor e o estilo do pacote vizinho.
- Beans transacionais e interceptados precisam ser chamados através do proxy Micronaut; evite self-invocation.
- Configurações novas devem ser tipadas em classes `@ConfigurationProperties` quando o módulo já segue esse padrão.
- Não edite artefatos sob `build/`, `.gradle/` ou fontes geradas.

## Navegação documental

- Visão e arquitetura: `docs/01-visao-geral.md`, `docs/02-arquitetura.md`, `docs/04-fluxo-ponta-a-ponta.md`
- Stack e dependências: `docs/03-tecnologias.md`
- Componentes: `docs/05-api-service.md` a `docs/07-core-mock.md`, `docs/16-feature-control-lib.md`, `docs/17-async-sync-redis.md`, `docs/19-adocao.md`
- Contratos e dados: `docs/08-eventos-e-contratos.md`, `docs/09-dados-redis-postgres.md`
- Resiliência e produção: `docs/11-resiliencia-e-tradeoffs.md`, `docs/15-prontidao-producao.md`
- Execução, testes e operação: `docs/12-execucao-e-operacao.md`, `docs/13-testes.md`, `docs/18-operacao-features.md`

## Checklist antes de encerrar

- O diff toca apenas o escopo declarado.
- Testes novos provam resultados observáveis e cobrem falhas relevantes.
- Schemas, tópicos, migrations, configuração e docs continuam coerentes.
- O gate do módulo passou; o gate completo foi executado quando a mudança cruzou módulos.
- Nenhum teste foi apagado, ignorado ou enfraquecido para obter sucesso.
- `git diff --check` não reporta whitespace inválido.
- Mudanças remotas, deploys e publicação de artefatos só ocorrem com autorização explícita.
