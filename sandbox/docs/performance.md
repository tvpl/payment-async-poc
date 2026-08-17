# Performance e capacidade local

O sandbox de nó único prova integração e comportamento de falha; não certifica capacidade produtiva. Kafka usa um broker/controller combinado, PostgreSQL uma instância, Redis um nó e Registry em memória.

Para testes de carga do workspace:

- monitore CPU, memória, disco, Kafka lag, conexões PostgreSQL e Redis;
- mantenha as retenções de `config/lifecycle.json` dentro do espaço disponível;
- execute carga somente após `make verify` verde;
- registre versão das imagens, profiles, recursos do Docker e data do relatório;
- não transforme resultados locais em claim de produção.

A meta cross-boundary (AD-007, supersede AD-006) de 1.000 req/min e spike de 2.000 req/min pertence ao gate final das aplicações, não a esta infraestrutura isolada.

## Dimensionamento de partições e concorrência (SCAL-03)

`sandbox/smoke/init.sh` cria os tópicos com `KAFKA_TOPIC_PARTITIONS` (default 6,
documentado em `sandbox/.env.example`). O valor é o dobro do default de
`threads` dos listeners Kafka do payment-sbus (3 — ver
`sbus.kafka.consumers.<nome>.threads` em `payment-sbus/src/main/resources/application.yml`),
dando folga sobre o alvo AD-007 sem precisar repartitionar depois: cada
listener já cobre sua fatia das partições com concorrência própria, e o dobro
de partições absorve rebalanceamentos e picos sem deixar threads ociosas.

Para aumentar a concorrência local:

- suba `KAFKA_TOPIC_PARTITIONS` em `sandbox/.env` (múltiplo do número de
  threads dos listeners, nunca menor — partições sobrando são inócuas,
  threads sobrando ficam ociosas);
- ajuste `threads` no payment-sbus (`sbus.kafka.consumers.*.threads`) e no
  payment-core-mock (`core.kafka.consumers.command.threads`) na mesma
  proporção;
- repartitionar um tópico existente não reordena mensagens já publicadas —
  aplique a mudança antes da carga, não em um tópico já em uso (runbook de
  produção citado no `design.md` da feature, não automatizado aqui).
