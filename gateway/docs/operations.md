# Operação — gateway

## Ordem de subida completa (E2E com gateway)

```bash
cd payment-contracts && ./gradlew publishAllToLocalBuildRepository && cd ..
cd feature-control && ./gradlew publishMavenPublicationToLocalBuildRepository && cd ..
cd sandbox && cp .env.example .env && make up && cd ..
cd payment-api && cp .env.example .env && docker compose --env-file .env up --build -d api && cd ..
cd payment-sbus && cp .env.example .env && docker compose --env-file .env up --build -d sbus && cd ..
cd payment-core-mock && docker compose up --build -d core-mock && cd ..
cd gateway && cp .env.example .env && make up
```

(Preencha os `.env` conforme a documentação de cada fronteira antes de subir.)

## Testar sem o gateway (caminho rápido)

Nada muda em relação ao fluxo histórico do workspace: não suba esta fronteira e
use `scripts/smoke.sh` da raiz contra `localhost:8080`. O gateway nunca é
dependência dos testes das outras fronteiras.

## Derrubar

```bash
make down        # mantém o volume de certificados
make clean       # remove também o volume (regenera certificados no próximo up)
```

## Sinais e diagnóstico

| O que | Onde |
| --- | --- |
| prontidão do proxy | `curl localhost:9901/ready` (responde LIVE) |
| métricas Prometheus | `curl localhost:9901/stats/prometheus` |
| config efetiva | `curl localhost:9901/config_dump` |
| estado dos clusters (circuit breaking, ejeção) | `curl localhost:9901/clusters` |
| access log JSON | `make logs` |
| console do Keycloak | `http://localhost:8086` (admin / senha do `.env`) |

Métricas úteis no admin: as famílias `envoy_cluster_upstream_rq` (por código),
`envoy_cluster_upstream_rq_retry`, `envoy_cluster_outlier_detection_ejections_active`
(circuito aberto) e `envoy_http_downstream_rq` por listener. O scrape não está
cadastrado no Prometheus do sandbox porque o gateway é opcional — cadastrar o
alvo `localhost:9901` é um passo manual de quem estiver investigando.

## Troubleshooting

| Sintoma | Causa provável | Ação |
| --- | --- | --- |
| `401 Jwt issuer is not configured` no smoke | token pedido por URL diferente de `http://localhost:8086` | use o issuer fixado; ver [configuration.md](configuration.md) |
| 401 com token recém-emitido | Keycloak reiniciou e o realm reimportou com chaves novas antes do cache JWKS expirar (5 min) | aguarde o refresh do JWKS ou reinicie o Envoy |
| POST devolve 404 no gateway, mas funciona direto no Edge | rota fora da allowlist (ex.: path com barra escapada é rejeitado por `path_with_escaped_slashes_action`) | confira a rota em `envoy/envoy.yaml` |
| 429 vindo do gateway em teste de carga | limites do `ratelimit/config.yaml` | teste de capacidade deve ir direto no Edge; o gateway não faz parte do alvo certificado |
| Envoy loga falha de DNS de `otel-collector` | profile observability do sandbox não está de pé | inofensivo; suba `make up-all` no sandbox se quiser traces |
| upstream 503 imediato | `payment-api` fora do ar ou ejetado por outlier detection | `curl localhost:9901/clusters` e logs do Edge |
