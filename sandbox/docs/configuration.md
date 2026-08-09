# Configuração

Copie `.env.example` para `.env`. O arquivo real é ignorado e nunca deve ser commitado.

## Obrigatórias

- `POSTGRES_PASSWORD`: necessária ao profile mínimo.
- `GRAFANA_ADMIN_PASSWORD`: necessária somente quando o overlay/profile de observabilidade é materializado.

## Rede, portas e volumes

`SANDBOX_NETWORK` altera o nome da rede. As variáveis `*_HOST_PORT` alteram binds do host. Portas válidas e duplicações são verificadas por `make verify-ports` em quatro combinações de profiles.

Os nomes dos cinco volumes podem ser alterados pelas variáveis `KAFKA_VOLUME`, `REDIS_VOLUME`, `POSTGRES_VOLUME`, `PROMETHEUS_VOLUME` e `GRAFANA_VOLUME`. Se forem alterados, `config/lifecycle.json` deve permanecer coerente com o Compose renderizado.

## Retenção

Kafka retém dados por 72 horas e limita cada partição local a 2 GiB. Redis usa AOF com `appendfsync everysec`. Prometheus retém sete dias ou 2 GB. PostgreSQL e Grafana persistem em volumes nomeados. O Registry em memória é explicitamente efêmero.
