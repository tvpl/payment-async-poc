# Operação

## Pré-requisitos

- JDK 21.
- Redis acessível em `localhost:6379` (ou `REDIS_HOST`/`REDIS_PORT`) para os testes `-PwithIT` e para rodar os exemplos.
- `JWT_SIGNATURE_SECRET` (≥32 bytes) para rodar `feature-demo`/`pilot-app` — sem default, o boot falha sem ele.

## Rodando um exemplo

```bash
JWT_SIGNATURE_SECRET=<segredo-dev-32-bytes> ./gradlew :feature-demo:run --no-daemon
curl http://localhost:8083/health
```

Nenhum dos exemplos é implantável em produção — ambos recusam boot sob `env=prod` (ver [segurança](security.md)). Não existe runbook de incidente aqui porque não existe tráfego real a proteger; um app consumidor real (fora deste boundary) é quem possui esse runbook.

## Release da biblioteca

```bash
./gradlew build -PwithIT --no-daemon
scripts/verify-docs.sh
bash scripts/verify-consumer-fixture.sh
```

`verify-consumer-fixture.sh` publica a biblioteca em um repositório Maven local temporário (`library/build/repo`), nunca em um repositório remoto — publicação real (GitHub Packages) fica fora da autorização automática e exige credenciais/aprovação explícitas separadas.

## Rollback

Reverter para uma versão anterior do GAV publicado é a mitigação padrão: como o `consumer-fixture` certifica que apps externos consomem apenas o artefato publicado (nunca fonte local), voltar a versão do GAV nos apps consumidores é suficiente — nenhuma migração de schema ou dado no Redis é necessária, já que o `key-prefix`/formato de flag são estáveis entre versões da biblioteca dentro deste boundary.
