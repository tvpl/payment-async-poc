# Configuração

## Comportamento determinístico

| Variável | Default | Regra |
| --- | ---: | --- |
| `CORE_LATENCY_MIN_MS` | `50` | inteiro não negativo e menor ou igual ao máximo |
| `CORE_LATENCY_MAX_MS` | `300` | inteiro não negativo e maior ou igual ao mínimo |
| `CORE_DECLINE_PCT` | `10` | 0..100 |
| `CORE_FAIL_PCT` | `0` | 0..100; soma com decline até 100 |
| `CORE_SEED` | `20260808` | seed estável combinado com `requestId` |

Configuração inválida recusa o startup. Não existe variável para remover a classificação `NON_PRODUCTION`.

## Dependências

| Variável | Local host | Compose na rede sandbox |
| --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `kafka:9092` |
| `APICURIO_REGISTRY_URL` | `http://localhost:8085/apis/registry/v2` | `http://registry:8080/apis/registry/v2` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | `http://otel-collector:4317` |
| `SANDBOX_NETWORK` | — | `payment-sandbox` |
| `CORE_MOCK_HOST_PORT` | — | `8082` |

O collector pode estar ausente no profile mínimo; falha de exportação não altera o contrato Kafka. Copie `.env.example` para `.env` somente para overrides locais e nunca registre segredos.

## Build e CI

`PAYMENT_CONTRACTS_REPOSITORY` aponta para o repositório Maven publicado. Repositórios autenticados usam `PAYMENT_CONTRACTS_REPOSITORY_USERNAME` e `PAYMENT_CONTRACTS_REPOSITORY_PASSWORD`; o CI injeta credenciais temporárias sem escrevê-las em arquivo. O build Docker usa `PAYMENT_CONTRACTS_REPOSITORY_CONTEXT`, um diretório Maven publicado fornecido como contexto nomeado.
