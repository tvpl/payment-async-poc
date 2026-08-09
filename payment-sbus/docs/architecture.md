# Arquitetura

O SBUS recebe eventos Kafka e mantém PostgreSQL como fonte durável. Cada mudança de estado cria a outbox correspondente na mesma transação. Um dispatcher faz claim limitado, publica fora da transação e confirma por token de ownership. Retry usa `next_attempt_at`; DLQ só termina em `DLQ_PUBLISHED` depois do ack.

Kafka e Registry falhos preservam a outbox ou o registro Kafka. PostgreSQL falho impede o retorno normal do consumer. Redis falho bloqueia a publicação ao Core sem fallback local multiplicável. Todos possuem timeout, tentativas e readiness obrigatória tipados.

O throughput nominal protegido do Core é 50 comandos/s. A meta cross-boundary de 167/s exige admissão e backlog limitados; não é um SLO terminal do SBUS isolado. Veja [performance](performance.md) e o [ADR do protocolo durável](adr/0001-transactional-outbox-and-durable-retry.md).
