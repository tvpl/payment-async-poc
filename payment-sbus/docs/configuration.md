# Configuração

`application.yml` contém os defaults locais. `application-prod.yml` exige `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_URI`, `POSTGRES_JDBC_URL`, usuário/senha, `APICURIO_REGISTRY_URL`, endpoint OTLP e identidade JWT assimétrica (`SBUS_JWT_JWKS_URL`, issuer e audience). Configuração incoerente recusa startup: os limites abaixo são validados entre si, não apenas lidos.

As tabelas desta página são verificadas contra `src/main/resources/application.yml` pelo gate de documentação (`scripts/validate_docs.py`). Um default que mudar no YAML e não aqui **quebra o gate** — esta página não pode divergir silenciosamente da configuração real.

## Outbox — `sbus.outbox.*`

O ciclo de publicação confiável. `lease` e `max-attempts` têm efeito de correção, não de tuning: um lease curto demais permite que duas instâncias publiquem a mesma linha.

| Chave | Default | Efeito |
| --- | --- | --- |
| `batch-size` | `100` | Linhas reivindicadas por ciclo do dispatcher |
| `max-attempts` | `8` | Tentativas antes de a linha virar `FAILED` e ir para a DLQ |
| `base-backoff` | `2s` | Primeiro intervalo do backoff exponencial |
| `max-backoff` | `5m` | Teto do backoff, para uma falha longa não virar espera infinita |
| `publish-timeout` | `30s` | Orçamento de uma publicação no broker |
| `poll-interval` | `200ms` | Frequência com que o dispatcher procura trabalho |
| `initial-delay` | `3s` | Atraso antes do primeiro ciclo após o boot |
| `lease` | `1m` | Validade de um claim antes de o reaper devolver a linha para `PENDING` |
| `retention` | `3d` | Idade de uma linha `PUBLISHED` antes da purga |

## Proteção do Core — `sbus.core.*`

O limite global que protege o Core de rajadas. É distribuído via Redis: o orçamento vale para a frota inteira, não por instância.

| Chave | Default | Efeito |
| --- | --- | --- |
| `limit-for-period` | `50` | Comandos ao Core admitidos por janela |
| `refresh-period` | `1s` | Tamanho da janela |

Este é o teto declarado do fluxo e a razão pela qual carga acima dele vira `202` na API, não erro. Ele **não** é um SLO terminal: a meta cross-boundary de 167 req/s depende de admissão e backlog limitados, não de o Core absorver tudo.

## Retry — `sbus.retry.*`

Falha transitória vai para um tópico de retry dedicado, não bloqueia a partição principal.

| Chave | Default | Efeito |
| --- | --- | --- |
| `max-attempts` | `5` | Tentativas no tópico de retry antes da DLQ |
| `base-delay` | `1s` | Primeiro intervalo entre tentativas |
| `max-delay` | `30s` | Teto do intervalo |

## Housekeeping — `sbus.housekeeping.*`

Purga em lotes, para que nenhuma tabela cresça indefinidamente.

| Chave | Default | Efeito |
| --- | --- | --- |
| `idempotency-retention` | `7d` | Idade de um registro de idempotência antes da purga |
| `message-retention` | `30d` | Idade de uma simulação terminal antes da purga |
| `batch-size` | `500` | Linhas apagadas por ciclo |
| `interval` | `1h` | Frequência do ciclo |

## Retenção declarada — `sbus.retention.*`

Declara o que o ambiente garante, para que o startup possa validar coerência.

| Chave | Default | Efeito |
| --- | --- | --- |
| `kafka-topic` | `7d` | Retenção do tópico no broker |
| `max-redelivery-window` | `1d` | Janela máxima em que uma redelivery é considerada possível |

A invariante que o startup exige: **a janela de redelivery precisa caber dentro de toda retenção que a dedupe depende**. Se uma mensagem pudesse ser reentregue depois de o registro de idempotência ter sido purgado, a redelivery deixaria de ser reconhecida como duplicata e viraria uma segunda simulação. Por isso `max-redelivery-window` (1d) é menor que `idempotency-retention` (7d), `message-retention` (30d) e `kafka-topic` (7d).

## Dependências — `sbus.dependencies.*`

Cada dependência tipa timeout, tentativas e participação em readiness. Nenhuma delas tem fallback local: o SBUS falha fechado.

| Dependência | `timeout` | `max-attempts` | `readiness-required` |
| --- | --- | --- | --- |
| `kafka` | `30s` | `8` | `true` |
| `postgresql` | `3s` | `3` | `true` |
| `redis` | `2s` | `1` | `true` |
| `registry` | `3s` | `3` | `true` |

`readiness-required: true` em todas significa que a instância se declara não-pronta quando qualquer uma cai — ela para de receber tráfego novo em vez de aceitar trabalho que não conseguiria sustentar. `redis` tem `max-attempts: 1` de propósito: o rate limiter do Core não tem substituto local, então insistir só adiaria a falha.

## Infraestrutura e codec

| Chave | Default | Efeito |
| --- | --- | --- |
| `micronaut.server.port` | `8081` | Porta HTTP |
| `datasources.default.maximum-pool-size` | `10` | Conexões JDBC; o dispatcher usa transações curtas para não segurá-las |
| `datasources.default.schema-generate` | `NONE` | O schema é do Flyway, nunca gerado pelo ORM |
| `kafka.consumers.default.max.poll.records` | `100` | Registros por poll |
| `payments.avro.codec-pool-size` | `8` | Codecs Apicurio disponíveis |
| `payments.avro.codec-acquire-timeout` | `250ms` | Espera máxima por um codec antes de tratar como falta de capacidade |

Os consumidores usam `ByteArrayDeserializer` e decodificam Avro explicitamente. É deliberado: falhar no deserializer transformaria uma mensagem envenenada em erro de infraestrutura, sem chance de roteá-la para a DLQ com os bytes originais.

## Segredos e build

`.env.example` não contém credencial. O `.env` local é ignorado e produção recebe secrets pelo mecanismo do ambiente.

O build da imagem recebe `contracts-repository` como contexto BuildKit contendo o layout Maven publicado. Em CI, `PAYMENT_CONTRACTS_REPOSITORY_CONTEXT` aponta para esse artefato imutável; não aponta para sources de outra fronteira.
