# Testes — gateway

## Gates locais

```bash
make config      # compose config -q + validação estrutural (realm, envoy, ratelimit)
make smoke       # E2E: Keycloak -> Envoy -> Edge -> Sbus -> Core
```

`make config` roda no CI (job `gateway` do workflow raiz) e não precisa de nada
no ar. `make smoke` exige o stack completo (ver [operations.md](operations.md)).

## O que o smoke prova

1. Token via password grant no client `payments-cli`.
2. Requisição sem token é barrada **no gateway** com 401 (nunca chega ao Edge).
3. Rota fora da allowlist devolve 404 do gateway.
4. POST autenticado atravessa até o Core e devolve 200/202/422 com `requestId`.
5. Polling do GET até desfecho terminal (`COMPLETED`/`FAILED`).
6. `/health/liveness` passa sem token.

## Cenários manuais úteis

mTLS (após `make certs-export`):

```bash
curl --cacert certs/local/ca.crt --cert certs/local/client.crt --key certs/local/client.key \
  https://localhost:10443/health/liveness
```

Sem certificado de cliente, o handshake no listener 10443 falha — é o
comportamento esperado, não um defeito.

Rate limit (com o limite por IP em 600/min, um laço de curl acima disso deve
começar a receber 429 com header `x-ratelimit-limit`):

```bash
for i in $(seq 1 700); do curl -s -o /dev/null -w '%{http_code}\n' \
  "http://localhost:10000/health/liveness"; done | sort | uniq -c
```

Circuit breaking: derrube o `payment-api` e observe
`envoy_cluster_outlier_detection_ejections_active` em `localhost:9901/stats/prometheus`
subir após 5 respostas 5xx consecutivas.

## O que NÃO testar por aqui

- Capacidade/carga: o alvo certificado do workspace (`load/`) é medido direto no
  Edge. O gateway adicionaria um salto e seus próprios limites à medição.
- Comportamento de idempotência/admissão: pertencem aos testes do `payment-api`.
