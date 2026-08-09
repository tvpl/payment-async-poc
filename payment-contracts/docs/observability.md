# Observabilidade

`AvroSerde.poolSnapshot()` expõe quatro valores observáveis:

| Campo | Significado |
| --- | --- |
| `capacity` | codecs fixos criados no startup |
| `available` | codecs livres |
| `borrowed` | codecs em uso |
| `timeouts` | aquisições que excederam o orçamento |

A biblioteca não escolhe backend de métricas. Cada consumidor adapta o snapshot ao sistema de telemetria local e preserva cardinalidade fixa. Tópico, payload, request id e dados de cliente não devem virar labels sem política explícita.

Alertas pertencem ao owner consumidor porque saturação depende da concorrência e do SLO daquele processo. O sandbox apenas agrega os artefatos de observabilidade fornecidos pelos serviços.
