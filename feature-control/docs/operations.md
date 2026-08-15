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

## Operando flags via admin API

O que segue documenta a mecânica do admin API que a biblioteca implementa e que `feature-demo` expõe
para demonstração (`FeatureAdminController`, porta 8083) — não é um runbook de incidente de produção
(isso continua sendo responsabilidade do app consumidor real). É a referência de como qualquer app que
adote `feature-control` opera flags no dia a dia; ver [adoção](adoption.md) para colocar o admin no ar
na sua app.

Todos os comandos exigem um JWT com `ROLE_ADMIN` (`intercept-url-map` em `/admin/**`). Token de dev:
`POST /auth/token {"userId":"op","groups":["ROLE_ADMIN"]}`.

### Conceitos

- **Baseline (YAML) vs override (Redis)**: o YAML é o padrão seguro que sobe com o deploy; o Redis
  sobrepõe em runtime (`CompositeFlagSource`). Remover o override (`DELETE`) volta ao baseline.
- **Propagação**: uma mudança se propaga em milissegundos para todas as instâncias via o canal
  `<key-prefix>changed`; o `cache-ttl` (5s por padrão) é só rede de segurança para quando o pub/sub
  falha — ver [arquitetura](architecture.md#propagação-cache-e-kill-switch).
- **`version`**: cada flag tem uma versão; toda escrita é compare-and-set (evita *lost update*
  entre dois operadores).

### Virar um toggle (A ↔ B, 100%)

```bash
curl -XPUT :8083/admin/features/demo-toggle -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"demo-toggle","type":"BOOLEAN","enabled":true,"onVariant":"service-b","offVariant":"service-a","version":<atual>}'
```

### Rollout gradual A/B (10% → 50% → 100%)

```bash
# aumente a porcentagem em passos; usuários já em "on" permanecem (sticky ao subir)
curl -XPUT :8083/admin/features/demo-ab -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"demo-ab","type":"PERCENTAGE","enabled":true,"percentage":50,"onVariant":"B","offVariant":"A","version":<atual>}'
```

Acompanhe pela métrica `feature_decisions_total` (ver [arquitetura](architecture.md#métricas-de-exposição)):
a razão on/off deve seguir a porcentagem configurada.

### Liberar v0 para um usuário/grupo (allowlist)

```bash
curl -XPUT :8083/admin/features/payment-api-v0 -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"payment-api-v0","type":"ALLOWLIST","enabled":true,"allowedGroups":["v0-testers"],"allowedUsers":["alice"],"onVariant":"v0","offVariant":"v1","version":<atual>}'
```

### Reverter um override (rollback)

```bash
# remove o override do Redis; a flag volta a resolver pelo baseline YAML
curl -XDELETE :8083/admin/features/demo-toggle -H "Authorization: Bearer $ADMIN"
```

Sem `?version=`, o `DELETE` lê a versão atual diretamente do store (nunca do resolver cacheado) antes
de apagar — ainda assim é CAS: uma escrita concorrente entre a leitura e o delete aparece como 409.

### Kill-switch (parada de emergência)

```bash
# desliga TODA avaliação de feature (tudo resolve off/default, reason=kill-switch)
curl -XPUT :8083/admin/features/__kill_switch__ -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"__kill_switch__","type":"BOOLEAN","enabled":true,"version":0}'
# reativa
curl -XDELETE :8083/admin/features/__kill_switch__ -H "Authorization: Bearer $ADMIN"
```

Alternativa estática, exige deploy: `platform.features.master-enabled=false`.

### Resolver conflito 409 (escrita concorrente)

Um `PUT`/`DELETE` retorna **409** quando a `version` enviada não é a atual (outro operador escreveu
antes):

1. Releia o estado atual (a versão vigente pode ser lida em `admin/FlagAdminService.currentVersion`,
   ou observada na resposta de um `GET` do lado da app, se exposto).
2. Reaplique a mudança com a `version` correta.

Nunca "force" — o 409 está protegendo contra sobrescrever a mudança do colega. Para criar um override
que ainda não existe, envie `version: 0`; repetir a criação com `version: 0` depois que ela já existe
retorna 409.

### Auditoria

Toda mutação aceita grava duas trilhas, no mesmo `EVAL` Lua da escrita (nunca uma sem a outra):

- **Stream Redis** `<key-prefix>audit-stream` (`XADD`): registro durável e ordenado de
  before/after/ator/versão/resultado/timestamp — a evidência autoritativa (FTR-04).
- **Log estruturado** (`logger=feature.audit`, MDC `actor`/`flag`/`action`) e uma lista capada
  `<key-prefix>audit` (`LPUSH`+`LTRIM`, até 1000 entradas) via `AuditService` — best-effort, útil para
  inspeção rápida sem ferramenta de stream: `LRANGE feature:audit 0 20`.

Um `DELETE` cujo `version` casa mas cuja flag já não existe não muda nada em Redis — e o registro de
auditoria diz isso: `result=noop`, não `result=ok` (AUD-21). `result=ok` fica reservado para uma
mutação que de fato aconteceu (chave removida ou criada/atualizada); um `noop` na trilha não é um erro
nem indica um `DELETE` que falhou — é a diferença entre "não havia nada para apagar" e "algo foi
apagado", visível em quem investiga a auditoria depois.

### Cuidados

- Baixar a porcentagem **tira** usuários de "on" (não é monotônico ao descer) — comunique antes de
  reduzir um rollout.
- Use sempre o mesmo `bucketingKey` (userId) para o A/B ser consistente entre serviços; para
  coordenar cohorts entre flags diferentes, defina o mesmo `bucketingSalt`.
- Nunca fail-open: em dúvida, erro ou kill-switch, o sistema resolve para baseline/off, nunca para on
  indefinido.
- Ver [operação local](#pré-requisitos) para subir `feature-demo` e testar esses comandos contra Redis
  real.

## Release da biblioteca

```bash
./gradlew build -PwithIT --no-daemon
scripts/verify-docs.sh
bash scripts/verify-consumer-fixture.sh
```

`verify-consumer-fixture.sh` publica a biblioteca em um repositório Maven local temporário (`library/build/repo`), nunca em um repositório remoto — publicação real (GitHub Packages) fica fora da autorização automática e exige credenciais/aprovação explícitas separadas.

## Rollback

Reverter para uma versão anterior do GAV publicado é a mitigação padrão: como o `consumer-fixture` certifica que apps externos consomem apenas o artefato publicado (nunca fonte local), voltar a versão do GAV nos apps consumidores é suficiente — nenhuma migração de schema ou dado no Redis é necessária, já que o `key-prefix`/formato de flag são estáveis entre versões da biblioteca dentro deste boundary.
