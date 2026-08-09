# Arquitetura

`payment-contracts` separa dados estáveis de integração do adapter tecnológico usado no wire Kafka.

```text
schemas/*.avsc
      |
      v
payment-contract-model  <--- payment-contract-avro-apicurio
      ^                              |
      |                              v
consumidores de modelo       produtores/consumidores Kafka
```

## Módulos

### payment-contract-model

Publica `EventEnvelope`, payloads, constantes de eventos/tópicos/headers e classes Avro geradas. Anotações de serialização preservam o contrato JSON existente. Não conhece Kafka client, Registry, Redis, banco ou HTTP.

### payment-contract-avro-apicurio

Publica `AvroMapper` e `AvroSerde`. O mapper traduz POJO e records Avro. O serde mantém um número fixo de pares serializer/deserializer não thread-safe, empresta um par por operação e o devolve em `finally`.

### consumer-fixture

É um build independente. Resolve somente os dois GAVs no repositório informado e executa round-trip Avro binário. Não pertence aos artefatos publicados.

## Limites

- Aplicações possuem autenticação, retry, idempotência, persistência e rate limiting.
- O sandbox possui Kafka e Apicurio locais.
- Esta fronteira define o contrato, não a política de processamento de cada consumidor.

Consulte [ADR-0001](adr/0001-contract-artifacts-and-compatibility.md) para a decisão de particionamento e compatibilidade.
