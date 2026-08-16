# payment-contracts

Owner dos contratos de evento do fluxo de pagamento. Esta fronteira publica o modelo estável e o adapter Avro/Apicurio como API Java independente de framework. Ela não contém controller, rate limiter, persistência, regra de negócio ou processo executável.

## Artefatos

| GAV | Conteúdo |
| --- | --- |
| `com.example.payments:payment-contract-model:0.2.0` | envelope, modelos, constantes e classes Avro geradas |
| `com.example.payments:payment-contract-avro-apicurio:0.2.0` | mapper e pool limitado de codecs Apicurio |

Consumidores declaram versões publicadas. Dependência `project()` entre fronteiras é proibida.

## Quickstart

Requisito: JDK 21. O build baixa dependências do Maven Central e não inicia Kafka, Registry ou outra infraestrutura.

```bash
./gradlew test --no-daemon
./gradlew checkSchemaCompatibility --no-daemon
./gradlew build publishAllToLocalBuildRepository verifyLocalPublication --no-daemon
scripts/verify-consumer-fixture.sh
scripts/verify-docs.sh
```

O repositório Maven de desenvolvimento fica em `build/repository`. A publicação externa não faz parte destes comandos e exige autorização específica.

## Fontes de verdade

1. [`schemas/*.avsc`](schemas) e [`schemas/manifest.json`](schemas/manifest.json);
2. código e testes de [`contract-model`](contract-model) e [`contract-avro-apicurio`](contract-avro-apicurio);
3. [`schemas/compatibility-policy.json`](schemas/compatibility-policy.json) e histórico versionado;
4. [documentação local](docs/README.md) e [ADRs](docs/adr/README.md).

## Dependências externas

- Maven Central no build;
- Apicurio Registry somente no runtime das aplicações consumidoras;
- Kafka somente no runtime das aplicações consumidoras.

Esta biblioteca não possui Dockerfile ou Compose porque não executa processo. A infraestrutura local pertence ao `sandbox` do workspace.

## Status

Fronteira extraível com gates locais. Prontidão dos serviços consumidores e certificação de carga são avaliadas nos respectivos owners; este README não declara prontidão produtiva do sistema.
