# Operações

## Pré-requisitos

Publique `payment-contracts` no repositório Maven local e inicie o sandbox mínimo. A rede `payment-sandbox`, Kafka e Registry devem estar saudáveis antes da aplicação.

## Executar pelo Gradle

```bash
./gradlew run --no-daemon
curl --fail http://localhost:8082/health
```

## Executar pelo Compose

```bash
cp .env.example .env
docker compose --env-file .env config -q
docker compose --env-file .env up --build --wait core-mock
docker compose --env-file .env logs -f core-mock
docker compose --env-file .env down
```

`down` desta raiz não usa `-v` e não remove a rede externa ou os volumes do sandbox.

## Diagnóstico

- startup recusado: confira bounds e soma de percentuais;
- conexão Kafka: confira `KAFKA_BOOTSTRAP_SERVERS` e membership da rede;
- decode/schema: confira `APICURIO_REGISTRY_URL` e a versão dos GAVs;
- partição parada: procure `remains uncommitted` nos logs e corrija poison/dependência antes de retomar;
- healthcheck: inspecione logs e `/health`; nunca neutralize o check para obter verde.

## Encerramento e recovery

Este owner pode reiniciar apenas seu processo. Reset de tópicos, remoção de volumes, prune ou `down -v` não fazem parte dos gates e exigem decisão operacional separada no sandbox. Uma falha de integração não executada deve constar como `NOT_RUN`.
