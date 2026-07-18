# 16 — Feature Control (lib compartilhada): v0, toggle, A/B e chave por usuário (JWT)

Biblioteca reutilizável (`feature-control`) que centraliza **controle de features** para as 30+
aplicações da plataforma: **API v0** para um grupo restrito, **feature toggle** (virar 100% para A ou
B), **A/B por porcentagem** e **chave por usuário/grupo** reconhecida no **JWT** — tudo com **controle
dinâmico em runtime** via Redis, sem redeploy. É um módulo `io.micronaut.library` (como `common`),
então cada app só declara a dependência e injeta os beans.

> **Onde no código:** `feature-control/src/main/java/com/example/platform/featurecontrol/…`
> **Exemplos executáveis:** serviço `feature-demo` (:8083) — um endpoint por cenário.
> **Integração real:** `api-service` expõe `/v0/payment-simulations` restrito ao grupo `v0-testers`.

---

## Por que uma lib (e não copiar em cada app)

Sem uma lib, cada um dos 30+ serviços reimplementa parsing de JWT, conexão Redis, bucketing A/B e
leitura de flags — com bugs sutis diferentes (bucketing não-sticky, fail-open perigoso, cache
inconsistente). A lib entrega **uma** implementação testada e **uma** semântica: mesma decisão, mesmo
`reason`, mesma chave de bucketing entre serviços. Um usuário no bucket "B" do checkout continua "B"
em todos os serviços que avaliam aquela flag.

## O que é / Por que / Como / Onde

| Peça | O que é | Onde |
|---|---|---|
| `FeatureContext` | Sujeito da avaliação (userId, tenant, groups, attrs) — agnóstico de HTTP/JWT | `context/FeatureContext.java` |
| `JwtFeatureContextFactory` | Extrai o contexto do `Authentication` (JWT): `sub`→userId, `roles`+claim `groups`→groups | `context/JwtFeatureContextFactory.java` |
| `FlagDefinition` | Definição da flag (tipo, enabled, %, allowlists, variantes) — mesmo JSON em YAML e Redis | `model/FlagDefinition.java` |
| `FeatureResolver` | **Coração**: aplica a estratégia e devolve `FeatureDecision` (variante + `reason`) | `resolver/FeatureResolver.java` |
| `Bucketer` | Hash estável (FNV-1a) → bucket `[0,100)`; determinístico/sticky e seleção ponderada | `bucketing/Bucketer.java` |
| `StaticFlagSource` | Baseline vinda do YAML (`platform.features.flags.*`) | `store/StaticFlagSource.java` |
| `RedisFlagSource` | Override dinâmico do Redis com cache curto e **degradação** (fail-safe) | `store/RedisFlagSource.java` |
| `CompositeFlagSource` | Camada: Redis (dinâmico) → YAML (baseline). É o `@Primary` | `store/CompositeFlagSource.java` |
| `FlagAdminService` | Write path: grava `feature:<name>` no Redis + invalida cache (flip em runtime) | `admin/FlagAdminService.java` |
| `TopicRouter` | Escolhe tópico Kafka A/B a partir da decisão | `kafka/TopicRouter.java` |
| `ApiVersionResolver` | Resolve `v0`/`v1` (explícito por path/header ou feature-gated) | `version/ApiVersionResolver.java` |

---

## Arquitetura

```mermaid
flowchart LR
    req[Request HTTP + JWT] --> ctxf[JwtFeatureContextFactory]
    ctxf --> ctx[FeatureContext\nuserId, groups, tenant]
    ctx --> res[FeatureResolver]
    subgraph sources[FlagSource]
      comp[CompositeFlagSource\n@Primary]
      comp --> redis[(RedisFlagSource\ndinâmico + cache 5s)]
      comp --> yaml[StaticFlagSource\nYAML baseline]
    end
    res --> comp
    res --> dec[FeatureDecision\nvariant + on + reason]
    admin[PUT /admin/features/name] --> adm[FlagAdminService] --> rk[(Redis feature:name)]
    redis -.lê.-> rk
```

A resolução é **pura**: `FeatureResolver.evaluate(flag, ctx)` não conhece HTTP nem JWT — só o
`FeatureContext`. Isso é o que permite a mesma lib rodar em qualquer app, com qualquer autenticação.

---

## Os quatro cenários

Todos passam pelo mesmo `evaluate`; o `type` da flag escolhe o ramo. Precedência: **allowlist
(usuário/grupo) → percentage/AB → variant ponderada → toggle → default off**.

### 1) Feature toggle (BOOLEAN) — virar 100% para A ou B

```yaml
platform.features.flags.demo-toggle:
  type: BOOLEAN
  enabled: true
  on-variant: service-b
  off-variant: service-a
```

```mermaid
flowchart LR
  a[evaluate demo-toggle] --> b{enabled?}
  b -- não --> off[service-a - disabled]
  b -- sim --> on[service-b - toggle:on]
```

Flip global e instantâneo (kill-switch, cutover A→B). `GET /demo/toggle` no `feature-demo`.

### 2) A/B por porcentagem (PERCENTAGE) — 10% / 90%, sticky por usuário

```yaml
platform.features.flags.demo-ab:
  type: PERCENTAGE
  enabled: true
  percentage: 10        # 10% -> on (B), 90% -> off (A)
  on-variant: B
  off-variant: A
```

O `Bucketer` calcula `bucket = FNV1a(flag + ":" + bucketingKey) mod 100`. Se `bucket < percentage` →
B. Como a chave é o **userId** (ou `X-Anon-Id`), o mesmo usuário cai **sempre** no mesmo lado
(*sticky*), e o `salt = flag` **descorrelaciona** flags diferentes (o usuário não é "azarado" em
todas). `GET /demo/ab` — repita com o mesmo `X-Anon-Id` e a variante não muda.

### 3) Chave por usuário/grupo (ALLOWLIST) — reconhecida no JWT

```yaml
platform.features.flags.demo-restricted:
  type: ALLOWLIST
  enabled: true
  allowed-users:  [vip-user]
  allowed-groups: [beta]
  on-variant: granted
  off-variant: denied
```

O `JwtFeatureContextFactory` lê `sub`→userId e `roles`+claim `groups`→groups do token validado. Se o
userId está em `allowed-users` **ou** um grupo bate `allowed-groups` → on (`reason=allowlist:user|group`);
senão off. Numa flag PERCENTAGE/VARIANT, a allowlist funciona como **override** (fixa o grupo piloto
no "on" independentemente da %). `GET /demo/restricted` com/sem Bearer.

### 4) API v0 — versão de teste para um grupo restrito

```yaml
platform.features.flags.payment-api-v0:
  type: ALLOWLIST
  enabled: true
  allowed-groups: [v0-testers]
  on-variant: v0
  off-variant: v1
```

Duas portas, um mesmo gate (`ApiVersionResolver`):
- **Explícito** — o frontend bate em `/v0/...` ou envia `X-Api-Version: v0`. O v0 é concedido só se o
  chamador é elegível; senão cai transparentemente para v1.
- **Feature-gated** — sem versão explícita: elegíveis recebem v0, os demais v1.

No `api-service`, `POST /v0/payment-simulations` (`controller/V0PaymentSimulationController.java`)
retorna **404** para quem não é do grupo (o v0 fica invisível) e, para elegíveis, **reusa o pipeline
já testado** (`ApiPaymentService`) adicionando os headers `X-Api-Version: v0` e `X-Feature-Reason`.

---

## JWT e gestão de contas

- Validação real via **micronaut-security-jwt** (HS256 no dev; **RS256/JWKS** em produção).
- Convenção de claims: `sub` = usuário; `roles` **e** claim `groups` = grupos; `tenant` = tenant.
- Emissor **dev** para testes: `POST /auth/token {"userId","groups"}` (`auth/DevTokenController.java`)
  — **não** é para produção; tokens reais vêm do seu IdP.
- No `feature-demo`/`api-service`, tudo é `isAnonymous()` na camada de segurança: um token válido é
  **decodificado** quando presente (para reconhecer usuário/grupo), mas endpoints existentes não são
  bloqueados — por isso ligar o security **não** altera o fluxo `/payment-simulations` já testado.

---

## Controle dinâmico (Redis) + fallback

`RedisFlagSource` lê `feature:<name>` (JSON) do Redis com **cache in-process** de TTL curto
(`platform.features.cache-ttl`, default 5s). O `CompositeFlagSource` sobrepõe Redis ao baseline YAML.

- **Flip em runtime:** `PUT /admin/features/{name}` grava no Redis e **invalida o cache** local; a
  instância que escreveu vê a mudança na hora, as demais em ≤ `cache-ttl`.
- **Fail-safe, nunca fail-open:** se o Redis cai ou o JSON é inválido, o source devolve vazio (ou o
  último valor bom em cache) e o **baseline YAML** aplica — nunca um estado indefinido/ligado.
- **Sem Redis:** `platform.features.redis-enabled=false` → só YAML (a lib segue funcionando).

---

## Como consumir em uma nova app (padrão para as 30+)

```groovy
// build.gradle
implementation project(':feature-control')          // ou o artefato publicado
implementation 'io.micronaut.security:micronaut-security-jwt'
implementation 'io.micronaut.redis:micronaut-redis-lettuce'  // para o store dinâmico
```

```java
@Controller("/checkout")
class CheckoutController {
    private final FeatureResolver features;
    CheckoutController(FeatureResolver features) { this.features = features; }

    @Get @Secured(SecurityRule.IS_ANONYMOUS)
    Object checkout(@Nullable Authentication auth) {
        FeatureContext ctx = JwtFeatureContextFactory.from(auth, null);
        return features.isEnabled("checkout-engine-v2", ctx) ? engineV2() : engineV1();
    }
}
```

Config baseline em `application.yml` (`platform.features.flags.*`) e pronto — o mesmo `PUT
/admin/features` controla todas as instâncias.

---

## Exemplos (curl) — `feature-demo` em :8083

```bash
make demo-features            # roteiro guiado dos 4 cenários (script abaixo)

# toggle
curl -s localhost:8083/demo/toggle

# A/B (sticky): mesmo X-Anon-Id -> mesma variante
curl -s -H 'X-Anon-Id: user-aaa' localhost:8083/demo/ab

# JWT de teste no grupo beta + v0-testers
TOKEN=$(curl -s -XPOST localhost:8083/auth/token -H 'Content-Type: application/json' \
  -d '{"userId":"alice","groups":["beta","v0-testers"]}' | jq -r .accessToken)

# restrito: 200 com token, 403 sem
curl -s -H "Authorization: Bearer $TOKEN" localhost:8083/demo/restricted
curl -s -o /dev/null -w '%{http_code}\n' localhost:8083/demo/restricted

# v0 vs v1
curl -s -H "Authorization: Bearer $TOKEN" localhost:8083/demo/version   # v0
curl -s localhost:8083/demo/version                                     # v1

# flip em runtime (admin exige ROLE_ADMIN)
ADMIN=$(curl -s -XPOST localhost:8083/auth/token -H 'Content-Type: application/json' \
  -d '{"userId":"admin","groups":["ROLE_ADMIN"]}' | jq -r .accessToken)
curl -s -XPUT localhost:8083/admin/features/demo-toggle -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-toggle","type":"BOOLEAN","enabled":false,"onVariant":"service-b","offVariant":"service-a"}'
curl -s localhost:8083/demo/toggle      # agora service-a
```

---

## Produção: propagação, métricas, governança e distribuição

### Propagação instantânea (Redis pub/sub)
Além do cache com `cache-ttl`, um flip é anunciado num canal `feature:changed`
(`store/FlagChangeNotifier.java`); cada app assina (`store/FlagChangeSubscriber.java`) e **invalida o
cache na hora** — a mudança aparece em todas as instâncias em milissegundos, e o `cache-ttl` vira só
uma rede de segurança. Reusa o mesmo padrão de pub/sub tolerante do `ResponseCoordinator`.

```mermaid
flowchart LR
  admin[PUT /admin/features/x] --> svc[FlagAdminService]
  svc -->|SET feature:x| r[(Redis)]
  svc -->|PUBLISH feature:changed x| ch((canal))
  ch --> s1[app 1: invalida cache]
  ch --> s2[app 2: invalida cache]
  ch --> s3[app N: invalida cache]
```

### Concorrência otimista (sem lost update)
Toda flag carrega uma `version`. O write é um **compare-and-set** atômico em Lua (usa o `cjson` do
Redis): grava só se a `version` enviada casa com a atual, incrementando-a; senão retorna **409** e o
cliente re-lê. Envie `version: 0` para criar; para atualizar, mande a versão que você leu.

```bash
# cria (version 0 -> 1)
curl -s -XPUT :8083/admin/features/x -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"x","type":"BOOLEAN","enabled":true,"version":0}'
# repetir com version:0 novamente -> HTTP 409 (conflito)
```

### Kill-switch global
`resolver/MasterSwitch.java` é consultado antes de qualquer flag. Dois gatilhos: estático
(`platform.features.master-enabled=false`) ou dinâmico (habilite a flag reservada `__kill_switch__`
via admin). Com ele ligado, toda decisão vira off/default com `reason=kill-switch` (fail-safe).

### Métricas de exposição (Micrometer)
Cada decisão emite `feature_decisions_total{flag,variant,on,reason_kind}` via
`metrics/MicrometerDecisionListener.java` (só ativo se houver `MeterRegistry`). É a base para ver o
rollout no Grafana (dashboard **Feature Decisions**) e para análise A/B. O `reason` é reduzido ao
"kind" (parte antes do `:`) para limitar cardinalidade. Extensível: implemente `spi/DecisionListener`
para exportar eventos de exposição à sua plataforma de experimentos.

### Governança do admin (ROLE_ADMIN + auditoria)
`/admin/features/**` exige `ROLE_ADMIN` (no `intercept-url-map` da segurança). Toda mudança é
auditada (`admin/AuditService.java`): log estruturado (MDC `actor`/`flag`/`action`) e, com Redis, uma
lista capada `feature:audit` (`LPUSH`+`LTRIM`). Em produção, adicione mTLS e um scope de admin real.

### `@FeatureGate` (açúcar opcional)
Em vez de resolver a flag na mão, anote o handler:

```java
@Get("/v2/report")
@FeatureGate("reporting-v2")   // 404 (ou 403) se a flag estiver off para o chamador
Report v2() { ... }
```

Interceptor AOP (`annotation/FeatureGateInterceptor.java`) lê o JWT do `ServerRequestContext` e nega
quando off (`FeatureDisabledException` → 404/403). Requer micronaut-aop + HTTP server; o resto da lib
funciona sem.

### Distribuição da lib (Maven)
A lib publica como artefato **`com.example.platform:feature-control:<versão>`** (com sources+javadoc),
então as 30+ apps consomem por versão, não por código-fonte:

```bash
./gradlew :feature-control:publishToMavenLocal          # dev
./gradlew :feature-control:publishMavenPublicationToLocalBuildRepository   # valida POM/artefato (CI)
GITHUB_ACTOR=... GITHUB_TOKEN=... \
  ./gradlew :feature-control:publishMavenPublicationToGitHubPackagesRepository   # GitHub Packages
```

```groovy
// numa app consumidora
implementation 'com.example.platform:feature-control:0.1.0'
```

O POM leva apenas as deps `api` (serde); Lettuce/segurança/micrometer ficam `compileOnly` — cada app
traz o runtime que já usa. SemVer: mudanças retrocompatíveis sobem o patch/minor; quebras, o major.

### JWT: dev (HS256) vs produção (RS256/JWKS)
Dev usa HS256 com segredo compartilhado (`JWT_SIGNATURE_SECRET`) e o emissor `POST /auth/token` só
para testes. Produção (`MICRONAUT_ENVIRONMENTS=prod`, `application-prod.yml`) valida contra o **JWKS**
do IdP (RS256, rotação de chaves automática) + `iss`; os tokens vêm do seu IdP, não deste serviço.

## Como testar

```bash
./gradlew :feature-control:test          # 20 testes unitários (bucketing, resolver, kill-switch, salt, store)
# ITs (JWT, governança, 409, kill-switch, métricas, flip) contra um Redis local, sem Docker:
REDIS_TEST_URI=redis://localhost:6379 ./gradlew :feature-demo:test -PwithIT
make demo-features                       # roteiro curl guiado dos cenários (stack no ar)
```

---

## Benchmark

O `feature-control` é chamado **por request**, então o custo precisa ser desprezível. Harness:
`load/k6-feature.js` (mede `decide_ms` = latência HTTP do endpoint `/demo/ab`, que inclui a decisão).

```bash
make load-feature K6_RATE=500 K6_DURATION=1m     # feature-demo em :8083
```

O que esperar (a decisão em si é: 1 hash FNV-1a + lookups em `HashMap`/cache in-process; o Redis só é
tocado a cada `cache-ttl`):

| Métrica | Ordem de grandeza esperada | Observação |
|---|---|---|
| Custo da decisão (in-process, cache quente) | sub-microssegundo | hash + map; sem I/O |
| `decide_ms` p99 (HTTP round-trip do demo) | poucos ms | dominado por rede/serialização, não pela decisão |
| Leituras ao Redis | 1 por flag a cada `cache-ttl` | não por request |

> Os números absolutos dependem da máquina; rode o harness no seu ambiente. O threshold do k6 já
> falha se `decide_ms p99 >= 50ms`, garantindo que a decisão não vira gargalo.

## Prós, contras e cuidados

**Prós**
- Uma semântica testada para 30+ apps; decisões auditáveis (`reason`).
- Bucketing determinístico/sticky e descorrelacionado por flag.
- Flip em runtime sem redeploy; baseline YAML seguro sempre presente.
- Custo por request desprezível (sem I/O no caminho quente).

**Contras / trade-offs**
- Cache curto ⇒ **janela de propagação** do flip (≤ `cache-ttl`). Menor TTL = flip mais rápido, mais
  leituras ao Redis.
- Sem "auditoria de quem mudou o quê" embutida (adicione no seu admin/gateway).
- Multivariado é bucketing por peso, não experimentação estatística com significância — para
  experimentos formais, exporte os eventos de exposição para sua plataforma de análise.

**Cuidados**
- Use **sempre a mesma chave** de bucketing (userId) para o A/B ser consistente entre serviços.
- **Nunca fail-open**: mantenha o baseline YAML conservador; se o Redis cair, é ele que vale.
- **Segredo JWT** só no dev via `.env`; produção com secret manager + **RS256/JWKS** (`application-prod.yml`).
- `/admin/features` já exige **ROLE_ADMIN** e é auditado; em produção acrescente mTLS/scope real.
- Em escrita concorrente, trate o **409** (re-leia a `version` e reenvie) em vez de forçar.
- `allowed-users`/`allowed-groups` grandes: prefira grupos (claim no JWT) a listas enormes de usuários.

## Ver também
- [17 Async→Sync via Redis (sem Kafka)](17-async-sync-redis.md) · [05 API service](05-api-service.md)
  · [09 Dados: Redis e PostgreSQL](09-dados-redis-postgres.md) · [15 Prontidão para produção](15-prontidao-producao.md)
