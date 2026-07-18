# 18 — Operação de features (runbook)

Guia prático para **operar** o controle de features em produção: virar flags, fazer rollout gradual,
reverter, usar o kill-switch, resolver conflitos e auditar. Complementa a [16](16-feature-control-lib.md)
(arquitetura) com o "como fazer no dia a dia".

> Todos os comandos usam o admin (`/admin/features/**`), que exige um JWT com **ROLE_ADMIN**.
> Obtenha um token (dev): `POST /auth/token {"userId":"op","groups":["ROLE_ADMIN"]}`.

## Conceitos operacionais

- **Baseline (YAML)** vs **override (Redis)**: o YAML é o padrão seguro que sobe com o deploy; o Redis
  sobrepõe em runtime. Remover o override (`DELETE`) volta ao baseline.
- **Propagação**: uma mudança vale em **milissegundos** em todas as instâncias (canal `feature:changed`);
  o `cache-ttl` (5s) é só rede de segurança.
- **version**: cada flag tem uma versão; escritas usam compare-and-set (evita *lost update*).

## Tarefas comuns

### Virar um toggle (A ↔ B, 100%)
```bash
curl -XPUT :8080/admin/features/checkout-engine -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"checkout-engine","type":"BOOLEAN","enabled":true,"onVariant":"engine-b","offVariant":"engine-a","version":<atual>}'
```

### Rollout gradual A/B (10% → 50% → 100%)
```bash
# aumente a porcentagem em passos; usuários já em "on" permanecem (sticky/monotônico ao subir)
curl -XPUT :8080/admin/features/checkout-engine -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"checkout-engine","type":"PERCENTAGE","enabled":true,"percentage":50,"onVariant":"engine-b","offVariant":"engine-a","version":<atual>}'
```
Acompanhe no Grafana (**Feature Decisions**): a razão on/off deve seguir a porcentagem.

### Liberar v0 para um usuário/grupo (allowlist)
```bash
curl -XPUT :8080/admin/features/payment-api-v0 -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"payment-api-v0","type":"ALLOWLIST","enabled":true,"allowedGroups":["v0-testers"],"allowedUsers":["alice"],"onVariant":"v0","offVariant":"v1","version":<atual>}'
```

### Reverter (rollback)
```bash
# volta ao baseline YAML removendo o override
curl -XDELETE :8080/admin/features/checkout-engine -H "Authorization: Bearer $ADMIN"
```

### Kill-switch (parada de emergência)
```bash
# desliga TODA avaliação de feature (tudo resolve off/default, reason=kill-switch)
curl -XPUT :8080/admin/features/__kill_switch__ -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"__kill_switch__","type":"BOOLEAN","enabled":true,"version":0}'
# reativa
curl -XDELETE :8080/admin/features/__kill_switch__ -H "Authorization: Bearer $ADMIN"
```
Alternativa estática (deploy): `platform.features.master-enabled=false`.

## Resolver conflito 409 (escrita concorrente)
Um `PUT` retorna **409** quando a `version` enviada não é a atual (outro operador escreveu antes):
1. **Re-leia** o estado atual (a resposta do 409 traz `expected`/`current`).
2. Reaplique sua mudança com a `version` correta.
Nunca "force" — o 409 está te protegendo de sobrescrever a mudança do colega.

## Auditoria
Toda mudança é registrada:
- **Log estruturado** (JSON) com `actor`, `flag`, `action` — filtre por `logger=feature.audit`.
- **Redis** (lista capada): `LRANGE feature:audit 0 20` mostra as últimas ações
  (`<timestamp> <action> <flag> by <actor>`).

## Consistência e cuidados
- Janela de propagação ≤ `cache-ttl` **se** o pub/sub falhar; normalmente é imediata.
- Baixar a porcentagem **tira** usuários de "on" (não é monotônico ao descer) — comunique.
- Use **sempre** o mesmo `bucketingKey` (userId) para o A/B ser consistente entre serviços; para
  coordenar coortes entre flags, defina o mesmo `bucketingSalt`.
- Nunca fail-open: em dúvida/erro/kill, o sistema resolve para o **baseline/off**.

## Como testar (local)
```bash
# sobe stack e valida os cenários + flip em runtime
make up && make demo-features
# ITs de governança (409, ROLE_ADMIN, kill-switch, métricas) contra Redis local:
REDIS_TEST_URI=redis://localhost:6379 ./gradlew :feature-demo:test -PwithIT
```

## Ver também
- [16 Feature Control (lib)](16-feature-control-lib.md) · [10 Observabilidade](10-observabilidade.md)
  · [15 Prontidão para produção](15-prontidao-producao.md)
