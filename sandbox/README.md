# Payment Sandbox

Infraestrutura compartilhada para desenvolvimento local. Esta fronteira não contém build, fonte ou contrato de produto e não representa um deployment de produção.

## Quickstart

Pré-requisitos: Docker Desktop com Compose, Bash, Python 3 e `curl`.

```bash
cd sandbox
cp .env.example .env
# Preencha POSTGRES_PASSWORD. Para observabilidade, preencha também GRAFANA_ADMIN_PASSWORD.
make up
make smoke
```

O profile mínimo inicia Kafka, Redis, PostgreSQL e Apicurio Registry. Para observabilidade e ferramentas:

```bash
make up-all
make verify
```

Use `make down` para parar e remover containers e a rede, preservando volumes. O reset de dados é deliberadamente separado e exige o token documentado em [Operação](docs/operations.md).

## Dependências e endpoints

| Capacidade | Endpoint do host | DNS na rede `payment-sandbox` |
| --- | --- | --- |
| Kafka | `localhost:29092` | `kafka:9092` |
| Redis | `localhost:6379` | `redis:6379` |
| PostgreSQL | `localhost:5432` | `postgres:5432` |
| Registry | `http://localhost:8085` | `http://registry:8080` |
| Prometheus | `http://localhost:9090` | `http://prometheus:9090` |
| Grafana | `http://localhost:3000` | `http://grafana:3000` |
| Jaeger | `http://localhost:16686` | `http://jaeger:16686` |
| Kafka UI | `http://localhost:8088` | `http://kafka-ui:8080` |

## Ownership e contratos

O sandbox publica infraestrutura e a rede local, não bibliotecas nem schemas de domínio. Tópicos locais refletem contratos publicados por `payment-contracts`; schemas de produto continuam sob o owner de contratos. Assets de observabilidade de aplicações entram somente pelo [manifest versionável](observability/application-assets.json).

## Operação

- [Arquitetura](docs/architecture.md)
- [Configuração](docs/configuration.md)
- [Segurança](docs/security.md)
- [Operação e troubleshooting](docs/operations.md)
- [Observabilidade](docs/observability.md)
- [Testes](docs/testing.md)
- [Performance](docs/performance.md)
- [ADRs](docs/adr/README.md)

Status: `LOCAL_DEVELOPMENT_INFRASTRUCTURE`. Claims de capacidade ou produção pertencem aos owners de aplicação e exigem relatórios datados.
