# Performance

`worker-concurrency` (padrão 2 por instância) e `pool-max-total` (padrão 64) limitam quanto trabalho concorrente cada instância sustenta; `pool-max-wait` e `wait-timeout` limitam quanto uma requisição HTTP pode esperar antes de cair para `202` com backpressure explícito. `process-latency-min/max-ms` simulam o custo de processamento do exemplo (20–150ms).

O cenário cross-boundary alvo é 10.000 req/min por 15 minutos e spike de 20.000 req/min por 60 segundos. Este pacote não declara certificação: throughput, backlog, PEL, latência de wakeup, percentis, heap e taxa de erro precisam constar em relatório datado do ambiente de referência. `load/k6-smoke.js` é um smoke funcional (mistura de status esperados), não um teste de capacidade.

Redis saturado ou indisponível não é absorvido com fila ilimitada: o pool de espera aplica backpressure (`202` + `X-Backpressure`), o rate limiter aplica `429`, e o backlog do stream aciona alerta antes do orçamento configurado — nunca perda silenciosa.
