# Performance

## Meta aprovada

Conforme AD-007 (supersede AD-006): 1.000 req/min sustentados por 15 minutos e spike de 2.000
req/min por 60 segundos, com latência média ≤ 300ms e p99 ≤ 10s. Excesso recebe `429`, `202` ou
buffering limitado. Perda silenciosa não é resultado aceitável em nenhum cenário.

## Onde está o custo

| Estágio | Limite real |
| --- | --- |
| Espera HTTP | virtual threads: milhares de esperas simultâneas sem thread de plataforma |
| Admissão | `limit-for-period` por janela na frota; degrada para a fração por instância sem Redis |
| Codec Avro | pool limitado (`codec-pool-size`); exaustão vira reentrega, não perda |
| Publicação | `acks=all`; a latência do broker entra no orçamento da requisição |
| Consumo | uma instância por partição; retry de aplicação bloqueia a partição por desenho |
| Fallback SBUS | `read-timeout` por chamada e circuito para o custo repetido |

O gargalo declarado é o Core a jusante, não a espera na API. Aumentar `wait-timeout` não aumenta
capacidade: apenas converte `202` em espera mais longa.

## Massa e execução

Os cenários k6 vivem em `load/k6-simulations.js` (submissão) e `load/k6-poll.js` (consulta de
status).

## Relatório

| Cenário | Data | Resultado |
| --- | --- | --- |
| Sustentado 1.000 req/min por 15 min | — | `NOT_RUN` |
| Spike 2.000 req/min por 60 s | — | `NOT_RUN` |
| Duas instâncias de API com coordenação cross-instance | — | `NOT_RUN` |

Nenhum número de capacidade desta fronteira está validado. Um relatório precisa registrar versão,
hardware, recursos de container, configuração, massa, duração, percentis, throughput, erro, lag,
backlog, GC, pools e banco antes de qualquer claim.
