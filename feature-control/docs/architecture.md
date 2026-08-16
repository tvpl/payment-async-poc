# Arquitetura

`feature-control` centraliza controle de features (feature toggle, A/B por porcentagem, chave por
usuário/grupo via JWT, multivariado) numa única lib em vez de cada app reimplementar parsing de JWT,
conexão Redis e bucketing A/B com bugs sutis diferentes (bucketing não-sticky, fail-open perigoso,
cache inconsistente). A lib entrega uma implementação testada e uma semântica única: mesma decisão,
mesmo `reason`, mesma chave de bucketing entre serviços — um usuário no bucket "B" de uma flag
continua "B" em todos os serviços que avaliam aquela flag. É um módulo Micronaut Library: cada app
consumidora só declara a dependência e injeta os beans (ver [adoção](adoption.md)).

## Componentes

```
FeatureResolver ──uses──> FlagSource (CompositeFlagSource = StaticFlagSource + RedisFlagSource)
      │                         │
      │                         └─ RedisFlagSource: cache last-known-good, maxStale, single-flight (FTR-02)
      │
      ├─ MasterSwitch: kill-switch global lido antes de qualquer estratégia
      ├─ Bucketer: hash determinístico (FNV-1a) para PERCENTAGE/VARIANT
      └─ publish ──> DecisionListener[] (MicrometerDecisionListener, LoggingDecisionListener)

FlagAdminService ──delegates──> VersionedFlagStore ──EVAL Lua──> Redis (flag key + audit stream, FTR-04)
      │
      └─ FlagChangeNotifier ──publish──> Redis pubsub ──> FlagChangeSubscriber (reconexão + convergência, FTR-03)
```

`FeatureResolver` é o único ponto de entrada de leitura: aplica exatamente uma estratégia por `FlagType` (`BOOLEAN`/`PERCENTAGE`/`ALLOWLIST`/`VARIANT`), consulta o `MasterSwitch` antes de tudo, e publica a decisão para os `DecisionListener`s registrados (métricas/logs). `FlagAdminService` é o único ponto de entrada de escrita: toda mutação passa por `VersionedFlagStore`, cujo script Lua faz o compare-and-set e grava a entrada de auditoria no mesmo `EVAL` — não há janela em que uma mutação aconteça sem seu registro de auditoria correspondente.

## Peças e onde encontrá-las

| Peça | Papel | Onde |
|---|---|---|
| `FeatureContext` | Sujeito da avaliação (userId, tenant, groups, attrs), agnóstico de HTTP/JWT | `context/FeatureContext.java` |
| `JwtFeatureContextFactory` | Extrai o contexto de uma `Authentication` (JWT): `sub`→userId, `roles`+claim `groups`→groups, claim `tenant`→tenant | `context/JwtFeatureContextFactory.java` |
| `FlagDefinition` | Definição da flag (tipo, enabled, %, allowlists, variantes), mesmo JSON em YAML, Redis e admin API | `model/FlagDefinition.java` |
| `Bucketer` | Hash estável (FNV-1a 64 bits) para bucket `[0,100)`, determinístico e sticky por `(salt, key)` | `bucketing/Bucketer.java` |
| `StaticFlagSource` | Baseline vinda do YAML (`platform.features.flags.*`) | `store/StaticFlagSource.java` |
| `RedisFlagSource` | Override dinâmico do Redis, cache com TTL jitterado e fallback last-known-good (FTR-02) | `store/RedisFlagSource.java` |
| `CompositeFlagSource` | Camada Redis (dinâmico) sobre YAML (baseline); é o `@Primary` `FlagSource` | `store/CompositeFlagSource.java` |
| `FlagAdminService` | Path de escrita: valida `actor`, delega o CAS a `VersionedFlagStore` | `admin/FlagAdminService.java` |
| `VersionedFlagStore` | CAS atômico via Lua contra Redis, grava mutação e auditoria no mesmo `EVAL` (FTR-04) | `store/VersionedFlagStore.java` |
| `FlagChangeNotifier`/`FlagChangeSubscriber` | Publica/assina o canal de invalidação `<key-prefix>changed`, com reconexão e backoff (FTR-03) | `store/FlagChangeNotifier.java`, `store/FlagChangeSubscriber.java` |
| `ApiVersionResolver` | Resolve `v0`/`v1` (explícito por path/header ou feature-gated) | `version/ApiVersionResolver.java` |
| `TopicRouter` | Escolhe tópico Kafka A/B a partir de uma decisão de flag | `kafka/TopicRouter.java` |

O padrão de consumo é sempre o mesmo, independente da app: declarar a dependência, definir o baseline
em YAML e injetar `FeatureResolver` (ou `ApiVersionResolver`/`TopicRouter` quando aplicável). O guia
completo, passo a passo, está em [adoção](adoption.md).

## Os quatro tipos de flag

Todo `FlagType` passa por `FeatureResolver.evaluate(flag, ctx)`; o tipo escolhe o ramo. Precedência:
**allowlist (usuário/grupo) → percentage/variant → toggle → default off**. Os quatro cenários abaixo
são os que `feature-demo` expõe (`examples/feature-demo`, porta 8083), um endpoint por cenário.

### BOOLEAN — feature toggle, 100% para A ou B

```yaml
demo-toggle:
  type: BOOLEAN
  enabled: true
  on-variant: service-b
  off-variant: service-a
```

Flip global e instantâneo (kill-switch pontual, cutover A→B). `GET /demo/toggle`.

### PERCENTAGE — A/B por porcentagem, sticky por usuário

```yaml
demo-ab:
  type: PERCENTAGE
  enabled: true
  percentage: 10        # 10% -> on (variante B), 90% -> off (variante A)
  on-variant: B
  off-variant: A
```

O `Bucketer` calcula `bucket = FNV1a(salt + ":" + bucketingKey) mod 100`; se `bucket < percentage`,
decide "on". O `salt` é o nome da flag por padrão (`bucketingSalt` pode fixar um valor para
correlacionar um grupo de flags no mesmo cohort). Como a chave é o `bucketingKey` do contexto
(userId, ou o atributo `anonId` quando anônimo), o mesmo usuário cai sempre no mesmo lado. `GET
/demo/ab` — repita com o mesmo `X-Anon-Id` e a variante não muda.

### ALLOWLIST — chave por usuário/grupo, reconhecida no JWT

```yaml
demo-restricted:
  type: ALLOWLIST
  enabled: true
  allowed-users: [vip-user]
  allowed-groups: [beta]
  on-variant: granted
  off-variant: denied
```

`allowedUsers()`/`allowedGroups()` também funcionam como *override* em flags `PERCENTAGE`/`VARIANT`:
fixam o grupo piloto em "on" independentemente da porcentagem ou do peso sorteado — o padrão comum de
"testers internos sempre veem a v0". `GET /demo/restricted` com/sem Bearer.

### VARIANT — multivariado, seleção ponderada

Pick ponderado entre `variants()` nomeadas (pesos não precisam somar 100; são normalizados). Sem
override de allowlist e com a lista de variantes vazia, resolve off; com uma lista não vazia mas cujos
pesos somam zero ou menos, `Bucketer.select` também resolve `null` (nunca cai para a primeira
variante da lista) e o resultado é off — não existe distribuição válida para escolher (AUD-22; na
prática inatingível por uma flag construída normalmente, já que o construtor de `FlagDefinition` já
rejeita uma flag VARIANT com peso total ≤ 0).

A variante escolhida ainda pode ser o `off-variant` configurado da flag — é uma escolha ponderada
válida como qualquer outra. Nesse caso `FeatureDecision.isOn()` é `false` mesmo a flag estando
`enabled` e tendo produzido uma escolha: `isOn` reflete se a variante escolhida é a de controle, não se
a flag "respondeu" (AUD-04). `TopicRouter`/`@FeatureGate`/`ApiVersionResolver`, que ramificam por
`isOn()`, dependem dessa distinção.

### Bônus: API v0 como ALLOWLIST

```yaml
payment-api-v0:
  type: ALLOWLIST
  enabled: true
  allowed-groups: [v0-testers]
  on-variant: v0
  off-variant: v1
```

`ApiVersionResolver` usa essa mesma flag para decidir a versão de API: **explícito** (o chamador bate
em `/v0` ou envia `X-Api-Version: v0` — concedido só se elegível, senão cai transparentemente para
v1) ou **feature-gated** (sem versão explícita: elegíveis recebem v0, os demais v1). A elegibilidade é
só mais uma flag, controlável em runtime como qualquer outra.

## Contexto de avaliação e JWT

`FeatureResolver.evaluate` é puro: não conhece HTTP nem JWT, só o `FeatureContext`. Isso é o que
permite a mesma lib rodar em qualquer app, com qualquer autenticação — `JwtFeatureContextFactory` é o
único ponto que conhece a convenção de claims (`sub`→userId, `roles`+claim `groups`→groups, claim
`tenant`→tenant), e é dependency-light o bastante para apps fora do Micronaut Security montarem um
`FeatureContext` diretamente pelo builder.

`bucketingKey()` (usado por `PERCENTAGE`/`VARIANT`) é sempre o `userId` quando autenticado; sem
`userId`, cai para o atributo `anonId` (ex.: header `X-Anon-Id`); sem nenhum dos dois, todo tráfego
anônimo bucketa junto sob a chave literal `"anonymous"`.

Validação de JWT é via `micronaut-security-jwt`: HS256 com segredo compartilhado (`JWT_SIGNATURE_SECRET`,
sem default — o boot falha sem ele) em dev; RS256/JWKS do IdP em produção (`application-prod.yml`). O
emissor de token de teste (`POST /auth/token`, `DevTokenController` em `feature-demo`) é explicitamente
não-produtivo — ver [segurança](security.md).

## Propagação, cache e kill-switch

`RedisFlagSource` lê `<key-prefix><flag>` do Redis com cache in-process (`cache-ttl`, default 5s,
jitterado por chave via `cache-ttl-jitter` para não sincronizar expiração entre flags e evitar
thundering herd no Redis). `CompositeFlagSource` sobrepõe esse resultado ao baseline YAML.

- **Propagação quase instantânea**: além do `cache-ttl`, todo flip publica no canal
  `<key-prefix>changed` (`FlagChangeNotifier`); cada instância assina (`FlagChangeSubscriber`, com
  reconexão e backoff jitterado configuráveis via `pubsub-reconnect-base-delay`/`-max-delay`) e
  invalida o cache local na hora — o `cache-ttl` vira rede de segurança para quando o pub/sub falha.
  `ConvergenceTracker` mede a latência publish→observação por instância e loga um alerta de
  degradação quando ela ultrapassa `convergence-alert-threshold` (padrão 2s).
- **Fail-safe, nunca fail-open**: se o Redis cai, um valor last-known-good continua servido só
  enquanto mais novo que `max-stale` (padrão 5m); passado isso, `stale-fallback` decide — `BASELINE`
  (volta ao YAML) ou `FAIL_CLOSED` (força off). Sem Redis (`redis-enabled=false`), a lib segue
  funcionando só com YAML.
- **Backoff de falha (AUD-14)**: single-flight só colapsa chamadores *simultâneos* — numa outage
  sustentada, a entrada do cache continua expirada, então cada chamador sequencial ainda entraria na
  fila do lock e tentaria o Redis de novo, cada um pagando um timeout de comando inteiro em série. Uma
  leitura que falha grava um prazo de backoff por chave (`failure-backoff`, padrão 1s, jitterado por
  `cache-ttl-jitter`); leituras dentro dessa janela servem a política de stale direto da entrada em
  cache, sem tocar o lock nem o Redis de novo. Uma leitura bem-sucedida limpa o prazo, então a
  recuperação retoma na próxima leitura assim que o Redis volta.
- **Refresh à prova de invalidação (AUD-26)**: `invalidate`/`invalidateAll` não tomam o lock de
  single-flight, então um `refresh` em voo pode ler o Redis antes de uma invalidação chegar e ainda
  assim escrever esse resultado agora obsoleto de volta no cache depois — revertendo silenciosamente
  a mutação por até um `cache-ttl` inteiro. Um contador de geração por chave (mais um global, para
  `invalidateAll`) é incrementado a cada invalidação; um `refresh` só grava seu resultado no cache se
  nenhuma invalidação aconteceu desde que ele começou.
- **Kill-switch global**: consultado por `MasterSwitch` antes de qualquer estratégia. Dois gatilhos:
  estático (`platform.features.master-enabled=false`, um ajuste de deploy) ou dinâmico (habilitar a
  flag reservada `__kill_switch__` via admin, que se propaga como qualquer outra flag). Ligado, toda
  decisão resolve para off/default com `reason=kill-switch`.
- **O kill-switch dinâmico trava através de uma outage do Redis (AUD-02)**: `find()` (a leitura
  pública) colapsa "chave não existe" e "Redis falhou" no mesmo `Optional.empty()` — insuficiente para
  o kill-switch, cuja direção segura é o oposto da do cache (`StalePolicy` prefere "off" na dúvida; um
  kill-switch precisa preferir "continua armado" na dúvida). `RedisFlagSource`/`CompositeFlagSource`
  expõem por isso uma leitura interna de três vias — `TrinaryFlagSource.findTrinary`, com
  `LookupOutcome` `FOUND`/`ABSENT`/`UNAVAILABLE` — consultada só por `MasterSwitch`, nunca pelo
  `FeatureResolver`. `MasterSwitch` mantém um latch (`lastKnownKilled`) que só é atualizado numa leitura
  `FOUND` (chave existe, Redis saudável) ou `ABSENT` (chave genuinamente removida, Redis saudável);
  numa leitura `UNAVAILABLE` (Redis fora do ar, ou o valor veio do fallback de stale-policy) o latch
  fica como estava. Cold start (nenhuma leitura anterior) começa destravado — sem estado local
  persistido não há como saber o estado dinâmico antes da primeira leitura bem-sucedida, então
  `master-enabled: false` em YAML continua sendo o break-glass de cold-start, não o latch.

## Escrita administrativa

`/admin/features/**` é o único caminho de escrita, sempre `ROLE_ADMIN` e sempre auditado no mesmo
`EVAL` Lua que faz o CAS — detalhes de governança, ator obrigatório e a trilha dupla de auditoria
(log estruturado + stream Redis) estão em [segurança](security.md#mutações-admin-ftr-04). A
distribuição da biblioteca como GAV Maven versionado (não como fonte) está em
[configuração](configuration.md#publicação-e-consumer-fixture).

## `@FeatureGate` (açúcar opcional)

Em vez de resolver a flag na mão, anota o handler:

```java
@Get("/v2/report")
@FeatureGate("reporting-v2")   // 404 (ou 403 com notFound=false) se a flag estiver off para o chamador
Report v2() { ... }
```

`FeatureGateInterceptor` (AOP) lê o JWT do `ServerRequestContext` e nega quando off
(`FeatureDisabledException` → 404/403). Requer `micronaut-aop` + servidor HTTP; o resto da lib
funciona sem essa dependência.

## Métricas de exposição

Cada decisão emite `feature_decisions_total{flag,variant,on,reason_kind}` via
`MicrometerDecisionListener` (só ativo se houver `MeterRegistry` no classpath). `reason_kind` é o
`reason` reduzido à parte antes de `:` (ex.: `percentage:bucket=37<40->on` vira `percentage`), para
limitar cardinalidade; `flag`/`variant` são bounded por `CardinalityGuard` (ver
[segurança](security.md#telemetria-sem-pii-ftr-05)). É a base para acompanhar um rollout em Grafana e
para análise A/B; para experimentação formal com significância estatística, implemente
`spi/DecisionListener` e exporte os eventos de exposição para sua plataforma de análise.

## Exemplos executáveis (curl) — `feature-demo` em :8083

```bash
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
  -d '{"name":"demo-toggle","type":"BOOLEAN","enabled":false,"onVariant":"service-b","offVariant":"service-a","version":0}'
curl -s localhost:8083/demo/toggle      # agora service-a
```

`DevTokenController` (`POST /auth/token`) é um emissor de JWT só para dev/teste; tokens reais vêm do
IdP configurado em produção.

## Custo em runtime

A decisão em si (`FeatureResolver.evaluate`) é in-process: um hash FNV-1a e lookups em cache/mapa, sem
I/O no caminho quente — o Redis só é tocado a cada `cache-ttl` (ou quando o pub/sub invalida a
entrada). Não há um harness de carga dedicado dentro deste boundary nem um gate de capacidade próprio
(consistente com o escopo: biblioteca + exemplos `NON_PRODUCTION`, sem tráfego real a proteger).
Existe um script exploratório em [`load/k6-feature.js`](../load/k6-feature.js) que mede `decide_ms`
através do `feature-demo`. Ele **não** está ligado a nenhum gate nem ao CI, então seus números não
são evidência: sirva-se dele para experimentar localmente, não para afirmar throughput.

## Fronteiras

- A biblioteca não conhece o fluxo de pagamento nem qualquer outro boundary do workspace (`StandaloneBoundaryTest` garante isso estruturalmente).
- `feature-demo`/`pilot-app` dependem apenas do projeto local `:feature-control` (não do GAV publicado) — a fronteira entre "biblioteca" e "consumidor real" só é exercida pelo `consumer-fixture`, que resolve exclusivamente o artefato publicado.
- Redis é a única dependência externa em runtime: store dinâmico de flags, canal de pubsub de invalidação e stream de auditoria compartilham a mesma instância, sob `key-prefix` configurável.

## Decisões que moldaram o desenho

- **CAS-com-auditoria-atômica em vez de dois passos** (FTR-04): mutação e auditoria no mesmo `EVAL` Lua elimina a janela em que um crash entre os dois deixaria uma mutação não auditada.
- **Cardinalidade e PII bounded por padrão** (FTR-05): `CardinalityGuard` e `SubjectHasher` protegem contra o caso comum de um app consumidor nunca configurar allowlists de tag — o limite é a política padrão, não algo que cada app precisa lembrar de configurar.
- **Exemplos nunca inicializam em produção** (SEC-01/SEC-02) — ver [ADR-0001](adr/0001-nonproduction-example-startup-guard.md).

## Trade-offs

**Prós**

- Uma semântica testada para todas as apps consumidoras; decisões auditáveis (`reason`).
- Bucketing determinístico/sticky e descorrelacionado por flag (salt = nome da flag por padrão).
- Flip em runtime sem redeploy; baseline YAML seguro sempre presente como piso.
- Custo por request desprezível (sem I/O no caminho quente).

**Contras**

- Cache com TTL implica uma janela de propagação normal do flip (mitigada a milissegundos pelo
  pub/sub, mas o `cache-ttl` continua sendo o pior caso).
- Não há "auditoria de quem mudou o quê" fora deste boundary — cada app consumidora que expõe o admin
  precisa da própria camada de acesso (`ROLE_ADMIN`) e, em produção, mTLS/escopo real.
- `VARIANT` é bucketing por peso, não experimentação estatística com significância; para experimentos
  formais, exporte os eventos de exposição (`spi/DecisionListener`) para uma plataforma de análise.

Testes que cobrem o comportamento descrito aqui (unidade, integração contra Redis real, gate de
publicação e compatibilidade) estão detalhados em [testes](testing.md).
