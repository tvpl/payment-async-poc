# Operação e troubleshooting

## Startup

```bash
cp .env.example .env
make up
make smoke
make up-all
make verify
```

`make up` inicia somente o núcleo. `make up-all` combina os dois Composes e habilita `observability` e `tools`.

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
- Docker sem espaço: pare. Não execute prune/reset como gate; libere espaço com autorização explícita.

## Shutdown e dados

`make down` preserva volumes. O único reset suportado é destrutivo e remove somente os cinco volumes declarados:

```bash
./scripts/reset-data.sh --confirm-destroy-sandbox-data
```

Não execute esse comando em smoke, CI ou troubleshooting comum. A remoção não é recuperável sem backup.
