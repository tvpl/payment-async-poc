# Observabilidade

Métricas em `/prometheus` (autenticado). Os nomes abaixo são verificados contra `SbusMetrics.java` pelo gate de documentação: uma métrica renomeada no código sem atualizar esta página **quebra o gate**, porque os alertas e dashboards da fronteira dependem desses nomes literais.

## Métricas

| Métrica | Tipo | O que responde |
| --- | --- | --- |
| `sbus_outbox_pending` | gauge | Quantas linhas ainda não foram publicadas. Crescimento sustentado significa que o dispatcher não está vencendo a entrada |
| `sbus_outbox_published_total` | contador | Vazão real de publicação |
| `sbus_outbox_publish_failures_total` | contador | Falhas de envio ao broker; sobe antes de a linha esgotar tentativas |
| `sbus_dlq_total` | contador | Mensagens roteadas para a DLQ |
| `sbus_dlq_unconfirmed` | gauge | Trabalho de DLQ ainda não confirmado pelo broker, incluindo claims ativos |
| `sbus_dlq_unconfirmed_oldest_age_seconds` | gauge | Idade do item de DLQ não confirmado mais antigo |
| `sbus_unrecoverable_message_total` | contador | Um registro cuja própria falha também não pôde ser persistida |
| `sbus_end_to_end_latency` | timer (p50/p95/p99) | Do evento de solicitação até o evento final |

## O que observar primeiro

**`sbus_unrecoverable_message_total > 0`** é o sinal mais grave da fronteira e deve alertar em qualquer valor acima de zero. Ele significa que tanto o handler principal quanto o agendador de retry/DLQ falharam — tipicamente o próprio PostgreSQL está fora. O registro não é perdido silenciosamente: o payload bruto vai para uma linha de log marcada, para replay manual. Mas essa métrica é o **único sinal automatizado** de que isso aconteceu.

**`sbus_dlq_unconfirmed_oldest_age_seconds` subindo** significa que existe falha terminal aguardando confirmação do broker há tempo demais. O alerta [`recoverable-dlq.yml`](../ops/alerts/recoverable-dlq.yml) dispara enquanto houver item pendente antigo. Runbook: [`dlq-unconfirmed.md`](../ops/runbooks/dlq-unconfirmed.md).

**`sbus_outbox_pending` crescendo com `sbus_outbox_published_total` estável** é backlog: a publicação parou de acompanhar a entrada. Se `sbus_outbox_publish_failures_total` também sobe, o broker é o problema; se não sobe, o limite do Core (`sbus.core.limit-for-period`) está segurando a cadência de propósito e o backlog é esperado. Runbook: [`retry-backlog.md`](../ops/runbooks/retry-backlog.md).

**`sbus_end_to_end_latency` no p99 subindo com o p50 estável** indica cauda, não degradação geral — normalmente retry acontecendo em uma fração do tráfego, não lentidão do Core.

Além das métricas próprias, acompanhe **consumer lag** por tópico e o **pool JDBC**: as transações aqui são curtas de propósito, então pool saturado costuma apontar para uma conexão presa em I/O, não para volume.

## Logs

Estruturados, propagando `requestId`, `correlationId`, `causationId` e `traceId` em todo o caminho de consumo e publicação. Nunca registre token, payload sensível, idempotency key integral ou conteúdo de `.env`.

A exceção deliberada é a mensagem irrecuperável descrita acima: ela registra o payload em base64 justamente porque não há mais nenhum lugar durável para guardá-lo, e perdê-lo seria perder o pagamento.

## Tracing

OTLP com `traceparent` replayado nos headers técnicos durante a publicação da outbox. É isso que mantém o trace contínuo através da fronteira: sem o replay, o trace terminaria no commit da transação e recomeçaria do zero no consumidor seguinte, quebrando a correlação exatamente no ponto mais interessante.

## Ownership

Dashboards e alertas de produto pertencem a `payment-sbus/ops`; o sandbox apenas os monta somente-leitura no ambiente local. O conteúdo das regras é responsabilidade desta fronteira, não da infraestrutura que as hospeda.
