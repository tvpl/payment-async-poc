# Configuração

Todas as propriedades vivem sob `platform.features.*`, bindadas por `FeatureSettings`.

| Propriedade | Padrão | Efeito |
| --- | --- | --- |
| `redis-enabled` | `true` | desliga o override dinâmico via Redis (YAML-only quando `false`). |
| `cache-ttl` | `5s` | janela de propagação de um flip; menor = flips mais rápidos, mais leituras no Redis. |
| `cache-ttl-jitter` | `0.1` | jitter fracionário por chave para evitar thundering herd na expiração. |
| `max-stale` | `5m` | por quanto tempo um valor last-known-good continua sendo servido após o Redis parar de responder. |
| `failure-backoff` | `1s` | por chave, quanto tempo uma leitura Redis falha é lembrada; leituras dentro da janela servem a política de stale direto do cache, sem lock nem nova chamada ao Redis — evita que uma outage sustentada faça cada thread enfileirada pagar um timeout de comando em série (AUD-14). Jitterado por `cache-ttl-jitter`. |
| `stale-fallback` | `BASELINE` | `BASELINE` (volta ao YAML) ou `FAIL_CLOSED` (força off) após `max-stale`. |
| `key-prefix` | `feature:` | namespace de chave Redis (`<prefix><flag>`, `<prefix>audit-stream`, `<prefix>reclaim-lease`). |
| `master-enabled` | `true` | kill-switch estático; `false` força toda flag a resolver off/default. |
| `convergence-alert-threshold` | `2s` | limite aprovado de latência publish→receive antes de `ConvergenceTracker` alertar. |
| `pubsub-reconnect-base-delay` / `-max-delay` | `200ms` / `30s` | bounds do backoff com jitter da reconexão pubsub. |
| `metric-cardinality-limit` | `200` | máximo de valores distintos de `flag`/`variant` rastreados antes de colapsar para `"other"` (FTR-05). |

## Publicação e consumer fixture

A biblioteca publica `com.example.platform:feature-control:0.1.0` (POM, jar, sources, javadoc) via `maven-publish`. `consumer-fixture` (build Gradle standalone, não incluído no `settings.gradle` raiz) resolve **somente** esse GAV através de um repositório Maven local (`exclusiveContent` reserva o grupo `com.example.platform`) — nenhuma substituição por `project(...)` é possível, mesmo por acidente.

`scripts/verify_api_surface.py` compara a superfície pública (`javap -public`) de oito classes prometidas contra `consumer-fixture/api-surface-baseline.txt`; um membro presente na baseline e ausente do jar atual falha o gate (breaking change), um membro novo não. Regenerar a baseline é uma ação deliberada: `python3 scripts/verify_api_surface.py --jar <jar> --baseline consumer-fixture/api-surface-baseline.txt --write-baseline`.

## Exemplos

`feature-demo`/`pilot-app` fixam `JWT_SIGNATURE_SECRET` (obrigatório, sem default) e apontam `redis.uri` para `redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}`. Nenhum dos dois expõe configuração de produção — ver [segurança](security.md).
