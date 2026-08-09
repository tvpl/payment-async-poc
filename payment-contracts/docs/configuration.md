# Configuração

## Build

| Propriedade | Valor atual | Finalidade |
| --- | --- | --- |
| `javaLanguageVersion` | `21` | toolchain local |
| `avroVersion` | `1.11.3` | geração e compatibilidade |
| `apicurioVersion` | `2.6.2.Final` | serde de Registry |
| `kafkaVersion` | `3.8.1` | API de serde fornecida pelo consumidor |

As versões pertencem a [`gradle.properties`](../gradle.properties). O build não lê propriedades do workspace pai.

## Convenção para o consumer do adapter

Estas chaves são uma convenção recomendada para a configuração pertencente à aplicação. A biblioteca não lê configuração nem depende de framework; a factory do consumer converte os valores e chama o construtor Java de `AvroSerde`.

| Chave no consumer | Default de transição | Argumento Java |
| --- | --- | --- |
| `payments.avro.registry-url` | fallback para `apicurio.registry.url` | `registryUrl` |
| `payments.avro.codec-pool-size` | `8` | `poolSize`, maior que zero |
| `payments.avro.codec-acquire-timeout` | `250ms` | `acquireTimeout`, positivo |
| `payments.avro.auto-register` | `true` | `autoRegister`; produção passa `false` |

O default de auto-registration pertence à configuração transitória dos consumidores legados, não à biblioteca e não é produtivo. Produção registra schemas por etapa autorizada e sua factory inicia `AvroSerde` com `autoRegister=false`.

O pool falha com `AvroCodecUnavailableException` quando não obtém codec dentro do orçamento. O consumidor decide como traduzir essa falha para retry, backpressure ou indisponibilidade.
