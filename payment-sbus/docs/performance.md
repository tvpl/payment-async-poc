# Performance

O SBUS limita comandos ao Core a 50/s por padrão. O lote de outbox é 100, o poll é 200 ms e os budgets de dependência são finitos. Retenção padrão: tópico/idempotência 7 dias, status 30 dias, outbox publicada 3 dias e redelivery máximo 1 dia.

O cenário cross-boundary alvo é 10.000 req/min por 15 minutos e spike de 20.000 req/min por 60 segundos. Este pacote não declara certificação: throughput, lag, backlog, drain time, percentis, heap, pools e erros precisam constar em relatório datado do ambiente de referência.

Core abaixo da entrada exige admissão ou backlog limitado. Kafka não justifica crescimento ilimitado, e o limite de 50/s não pode ser apresentado como SLO terminal de 167/s.
