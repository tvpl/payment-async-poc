# Configuração

## Build

| Propriedade | Valor atual | Finalidade |
| --- | --- | --- |
| `javaLanguageVersion` | `21` | toolchain local |
| `avroVersion` | `1.11.3` | geração e compatibilidade |
| `apicurioVersion` | `2.6.2.Final` | serde de Registry |
| `kafkaVersion` | `3.8.1` | API de serde fornecida pelo consumidor |

As versões pertencem a [`gradle.properties`](../gradle.properties). O build não lê propriedades do workspace pai.

## Runtime do adapter

| Propriedade | Default de transição | Regra |
| --- | --- | --- |
| `payments.avro.registry-url` | fallback para `apicurio.registry.url` | endpoint do Registry |
| `payments.avro.codec-pool-size` | `8` | deve ser maior que zero |
| `payments.avro.codec-acquire-timeout` | `250ms` | deve ser positivo |
| `payments.avro.auto-register` | `true` | consumidores em produção definem `false` |

O default de auto-registration preserva consumidores legados durante a migração. Ele não é um default produtivo. Produção registra schemas por etapa autorizada e inicia com `payments.avro.auto-register=false`.

O pool falha com `AvroCodecUnavailableException` quando não obtém codec dentro do orçamento. O consumidor decide como traduzir essa falha para retry, backpressure ou indisponibilidade.
