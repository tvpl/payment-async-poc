# Arquitetura

O sandbox possui dois arquivos Compose. `compose.yml` é o núcleo mínimo. `compose.profiles.yml` é um overlay carregado explicitamente para `observability` e `tools`.

```text
application compose -- external network --> payment-sandbox
                                           |-- Kafka
                                           |-- Redis
                                           |-- PostgreSQL
                                           |-- Registry
                                           |-- observability (optional)
                                           `-- tools (optional)
```

O Compose mínimo cria a rede nomeada `payment-sandbox`. Futuras aplicações declaram essa rede como externa e resolvem dependências pelo DNS da tabela do [README](../README.md#dependências-e-endpoints). O sandbox não conhece release, build ou ciclo de vida de aplicação.

O overlay isolado impede que secrets opcionais, como a senha administrativa do Grafana, sejam interpolados durante startup mínimo. A decisão e alternativas estão no [ADR-0001](adr/0001-shared-infrastructure-and-external-network.md).
