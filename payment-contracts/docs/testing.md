# Testes e gates

## Gate rápido

```bash
./gradlew test --no-daemon
```

Cobre modelo/JSON, constantes, todos os records e envelopes Avro, cinco round-trips, pool limitado, timeout, falha e virtual threads.

## Compatibilidade

```bash
./gradlew checkSchemaCompatibility --no-daemon
```

Compara os schemas atuais com todas as versões históricas nas duas direções. Mutações sintéticas provam que remoção requerida e troca incompatível de tipo falham.

## Publicação e consumo

```bash
./gradlew build publishAllToLocalBuildRepository verifyLocalPublication --no-daemon
scripts/verify-consumer-fixture.sh
```

Valida POM/JAR/sources/Javadoc e round-trip binário por um build sem source substitution. Repositório ausente e POM divergente são cenários negativos obrigatórios.

## Integração externa

Testes com Registry real exigem ambiente fornecido pelo sandbox. Sem esse ambiente o resultado é `NOT_RUN`. Unitários injetam codec determinístico para provar concorrência e falha sem rede; isso não é apresentado como teste de disponibilidade do Registry.
