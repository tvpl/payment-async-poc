# Configuração — gateway

## Variáveis (`.env`)

| Variável | Default | Uso |
| --- | --- | --- |
| `KEYCLOAK_ADMIN_USER` | `admin` | usuário administrativo do Keycloak |
| `KEYCLOAK_ADMIN_PASSWORD` | — (`:?`, obrigatório) | senha do console; compose aborta sem ela |
| `GATEWAY_HOST_PORT` | `10000` | listener HTTP do Envoy no host |
| `GATEWAY_MTLS_HOST_PORT` | `10443` | listener mTLS do Envoy no host |
| `GATEWAY_ADMIN_HOST_PORT` | `9901` | admin do Envoy (métricas, /ready, config dump) |
| `KEYCLOAK_HOST_PORT` | `8086` | Keycloak no host (issuer dos tokens) |
| `SANDBOX_NETWORK` | `payment-sandbox` | rede externa criada pelo sandbox |

## Acoplamentos que não são variáveis

Dois valores estão fixados no `envoy/envoy.yaml` e casam com defaults do compose:

1. **Issuer JWT** — `http://localhost:8086/realms/payments`. O compose fixa
   `KC_HOSTNAME` no mesmo valor para que o issuer seja idêntico visto do host e
   de dentro da rede. Se mudar `KEYCLOAK_HOST_PORT`, mude o issuer no
   `envoy.yaml` junto.
2. **Upstream do Edge** — `api:8080`, o alias DNS do serviço `api` do
   `payment-api/compose.yaml` na rede do sandbox.

Isso é deliberado: o arquivo do Envoy é estático e auditável, sem templating.
O dia em que precisar de variação por ambiente, o caminho certo é um arquivo
por ambiente, não interpolação.

## Limites de rate limit

Em `ratelimit/config.yaml` (domínio `payment-gateway`):

| Descritor | Limite | Intenção |
| --- | --- | --- |
| `remote_address` | 600/min | teto por origem, todas as rotas |
| `generic_key=payment-simulations` | 100/s | teto global da rota estável |
| `generic_key=v0-payment-simulations` | 25/s | teto global da rota beta (anônima no Edge) |

Regra de calibração: sempre mais frouxo que a admissão do Edge (20/s por rota e
por tenant), para que os 429 vistos em teste continuem vindo da camada que
conhece o Core. O serviço responde em fail-open (`failure_mode_deny: false`):
Rate Limit Service fora = tráfego passa.

Mudou descritor na rota ou no config? `make config` falha se os dois lados não
casarem (`scripts/validate-config.py`).

## Timeouts e retries (envoy.yaml)

| Rota | Timeout | Retry |
| --- | --- | --- |
| `POST /payment-simulations` | 6s | só `connect-failure`/`refused-stream`, 2x |
| `GET /payment-simulations/{id}` | 5s | `5xx,reset,connect-failure,refused-stream`, 2x, 2s por tentativa |
| `/v0/payment-simulations` | 6s | igual ao POST |
| `/health/` | 3s | nenhum |

## Identidades de teste importadas

| Credencial | Valor | Uso |
| --- | --- | --- |
| usuário | `alice` / `alice-change-me` | password grant no client `payments-cli` |
| usuário | `ops-admin` / `ops-admin-change-me` | idem, com role `payments-admin` |
| client m2m | `payments-m2m` / `dev-m2m-secret-change-me` | client credentials |

Todos são de teste por definição (sufixo `-change-me`) e nunca devem migrar
para um ambiente exposto.
