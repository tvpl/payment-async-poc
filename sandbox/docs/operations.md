# Operação e troubleshooting

## Pré-requisitos

Docker Desktop com Compose, Bash, Python 3 e `curl`. Detalhes de variáveis em
[configuração](configuration.md).

## Atalhos (Makefile)

```bash
make config            # valida o Compose renderizado (mínimo + overlay)
make up                # sobe o núcleo (Kafka, Redis, PostgreSQL, Registry)
make up-all             # núcleo + overlay, com profiles observability e tools
make down               # para containers e remove a rede; preserva volumes
make smoke              # ./smoke/verify.sh - probes de Kafka/Redis/PostgreSQL/Registry
make verify-profiles    # readiness de Prometheus/Jaeger/Grafana/Kafka UI
make verify-ports       # portas válidas e sem duplicação, quatro combinações de profile
make verify-lifecycle   # pins de imagem, retenções e confirmação de reset
make verify-docs        # links, comandos e claims da documentação
make verify-structural  # config + verify-ports + verify-lifecycle + verify-docs + testes locais
make verify-runtime     # smoke + verify-profiles
make verify             # verify-structural + verify-runtime
```

## Startup

```bash
cp .env.example .env
# preencha POSTGRES_PASSWORD; para observabilidade, também GRAFANA_ADMIN_PASSWORD
make up
make smoke
make up-all
make verify
```

`make up` inicia somente o núcleo. `make up-all` combina os dois Composes e habilita
`observability` e `tools`. `docker compose ps` mostra `healthy` para `kafka`, `redis`, `postgres`
e `registry` quando cada um passa no próprio healthcheck do Compose — use isso para esperar antes
de rodar smoke ou conectar uma aplicação.

## Desenvolvimento local (apps fora de compose)

Aplicações podem rodar fora de container (ex.: via Gradle) e ainda assim usar a infraestrutura do
sandbox, apontando para os endpoints do host da tabela em
[README#dependências-e-endpoints](../README.md#dependências-e-endpoints) (`localhost:29092` para
Kafka, `localhost:6379` para Redis, etc.). O sandbox só precisa estar de pé (`make up` ou
`make up-all`); como cada app configura seu próprio processo local é responsabilidade do owner da
aplicação.

## Inspeção rápida

```bash
# Schemas registrados no Registry
curl -s http://localhost:8085/apis/registry/v2/search/artifacts | jq .
```

Estado e schema de aplicação (ex.: tabelas do PostgreSQL, tópicos específicos) pertencem a cada
app; o sandbox só garante que a dependência esteja acessível. Para inspecionar mensageria
visualmente (tópicos, mensagens decodificadas, partições, consumer groups e lag), use o
[Kafka UI](observability.md#kafka-ui-inspeção-de-mensageria) em `:8088`.

## Diagnóstico

O smoke imprime a dependência e o comando de logs. Para inspeção direta:

```bash
docker compose -f compose.yml ps
docker compose -f compose.yml logs kafka
make verify-ports
make verify-lifecycle
```

- Falha de interpolação: preencha a variável indicada em `.env`.
- Porta ocupada: altere o respectivo `*_HOST_PORT` e rode `make verify-ports`.
- Kafka UI demora após recreate: aguarde `/actuator/health` retornar `UP` e repita `make verify-profiles`.
- Registry reiniciado: seu armazenamento é efêmero; rode `make smoke` para recriar a regra/schema sintético. Contratos de produto são republicados pelo owner de contratos.
- App não exporta trace: confirme que o profile `observability` está de pé (`make up-all`) antes
  de iniciar uma aplicação que exporta OTLP por padrão, ou desligue o exporter na config da
  própria app.
- Docker sem espaço: pare. Não execute prune/reset como gate; libere espaço com autorização explícita.

## Shutdown e dados

`make down` preserva volumes. O único reset suportado é destrutivo e remove somente os cinco volumes declarados:

```bash
./scripts/reset-data.sh --confirm-destroy-sandbox-data
```

Não execute esse comando em smoke, CI ou troubleshooting comum. A remoção não é recuperável sem backup.

## Ver também

- [Configuração](configuration.md) · [Arquitetura](architecture.md) · [Observabilidade](observability.md)
  · [Segurança](security.md) · [Testes e gates](testing.md)
