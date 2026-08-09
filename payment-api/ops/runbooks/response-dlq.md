# Runbook — evento final na DLQ

## Sinal

`api_response_dead_lettered_total` acima de zero, ou registros novos em `payment.simulation.dlq`.

Cada registro na DLQ é um resultado de pagamento que **não** foi aplicado. O cliente pode ter
recebido `202` e nunca verá o desfecho no Redis até isto ser resolvido.

## Diagnóstico

Leia os headers do registro:

| Header | Leitura |
| --- | --- |
| `x-dlq-stage: decode` | a mensagem não é decodificável: schema incompatível, bytes corrompidos ou tipo de evento errado no tópico |
| `x-dlq-stage: apply` | a mensagem é válida; o Redis não aceitou a escrita dentro do orçamento de retry |
| `x-dlq-origin-topic` | de qual tópico terminal veio |
| `x-dlq-reason` | classe e mensagem da exceção |

1. Estágio `apply` em volume: verifique a saúde do Redis. É uma falha de infraestrutura, e o
   payload original está preservado no registro.
2. Estágio `decode` em volume logo após um deploy: suspeite de incompatibilidade de schema no
   Apicurio entre produtor e consumidor.
3. Estágio `decode` isolado: provável evento fora do contrato publicado no tópico errado.

## Ação

1. Corrija a causa (Redis de volta, schema compatível, produtor corrigido).
2. Reprocesse republicando os bytes originais do registro no tópico de origem indicado por
   `x-dlq-origin-topic`, com a mesma chave. O consumo é idempotente: se o desfecho já tiver sido
   aplicado nesse meio tempo, a repetição é ignorada sem alterar o resultado.
3. Confirme pela consulta de status que o `requestId` chegou a estado terminal.

## Não fazer

- Não descarte um registro da DLQ sem confirmar o estado terminal do `requestId`: descartar é
  perder o desfecho de um pagamento.
- Não reprocesse antes de corrigir a causa; o registro voltaria para a DLQ.
