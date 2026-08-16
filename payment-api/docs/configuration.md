# Configuração

Toda propriedade abaixo é tipada e validada no startup. Configuração incoerente derruba o boot em
vez de degradar silenciosamente em produção.

## Fluxo e retenção

| Propriedade | Default | Efeito |
| --- | --- | --- |
| `payment.simulation.wait-timeout` | `3s` | quanto a requisição HTTP espera pelo resultado |
| `payment.simulation.status-ttl` | `15m` | validade da entrada de status no Redis |
| `payment.simulation.idempotency-ttl` | `24h` | validade da reserva de idempotência (janela publicada no contrato) |
| `payment.simulation.publish-lease` | `30s` | tempo em que uma tentativa de publicação é considerada em voo |
| `payment.simulation.response-channel` | `payment-sim-responses` | canal pub/sub que acorda waiters |

Invariante validada: `idempotency-ttl >= status-ttl`. Se a reserva expirasse antes do status, uma
resubmissão escaparia da deduplicação enquanto o original ainda estivesse visível.

## Admissão

| Propriedade | Default | Efeito |
| --- | --- | --- |
| `payment.simulation.rate-limit.limit-for-period` | `20` | orçamento da rota por janela, na frota |
| `payment.simulation.rate-limit.tenant-limit-for-period` | `10` | orçamento de um tenant por janela |
| `payment.simulation.rate-limit.instances` | `${PAYMENT_API_INSTANCES:1}` | réplicas que dividem o orçamento |
| `payment.simulation.rate-limit.refresh-period` | `1s` | tamanho da janela |

`instances` precisa refletir o número real de réplicas: é o divisor do orçamento degradado quando o
Redis está fora. Um valor menor que a frota permite admitir mais do que a rajada aprovada.
Validações: `tenant-limit-for-period <= limit-for-period` e `instances >= 1`.

## Consumo de respostas

| Propriedade | Default | Efeito |
| --- | --- | --- |
| `payment.response-consumer.max-attempts` | `3` | tentativas de aplicar um evento final antes da DLQ |
| `payment.response-consumer.retry-delay` | `500ms` | pausa entre tentativas (bloqueia a partição por desenho) |

## Fallback durável

| Propriedade | Default | Efeito |
| --- | --- | --- |
| `micronaut.http.services.sbus.url` | `${SBUS_BASE_URL}` | endereço do SBUS |
| `micronaut.http.services.sbus.connect-timeout` | `500ms` | orçamento de conexão |
| `micronaut.http.services.sbus.read-timeout` | `800ms` | orçamento de leitura de **uma** chamada |
| `payment.sbus.failure-threshold` | `5` | falhas consecutivas que abrem o circuito |
| `payment.sbus.open-duration` | `30s` | tempo com o circuito aberto |

## Codec

| Propriedade | Default | Efeito |
| --- | --- | --- |
| `payments.avro.codec-pool-size` | `8` | codecs Apicurio disponíveis |
| `payments.avro.codec-acquire-timeout` | `250ms` | espera máxima por um codec |

## Segurança

Ver [security.md](security.md). Em `prod` o startup exige JWKS/issuer/audience e recusa defaults de
desenvolvimento.
