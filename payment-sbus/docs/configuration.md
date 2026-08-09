# Configuração

`application.yml` contém defaults locais. `application-prod.yml` exige `KAFKA_BOOTSTRAP_SERVERS`, `REDIS_URI`, `POSTGRES_JDBC_URL`, usuário/senha, `APICURIO_REGISTRY_URL`, endpoint OTLP e identidade JWT assimétrica (`SBUS_JWT_JWKS_URL`, issuer e audience).

`sbus.dependencies.*` tipa timeout, tentativas e participação em readiness para Kafka, PostgreSQL, Redis e Registry. `sbus.retention.kafka-topic` e `max-redelivery-window` são validados contra retenções de idempotência, estado e outbox. Configuração incoerente recusa startup.

`.env.example` não contém credencial. O `.env` local é ignorado e produção recebe secrets pelo mecanismo do ambiente.

O build da imagem recebe `contracts-repository` como contexto BuildKit contendo o layout Maven publicado. Em CI, `PAYMENT_CONTRACTS_REPOSITORY_CONTEXT` aponta para esse artefato imutável; não aponta para sources de outra fronteira.
