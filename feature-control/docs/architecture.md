# Arquitetura

## Componentes

```
FeatureResolver ──uses──> FlagSource (CompositeFlagSource = StaticFlagSource + RedisFlagSource)
      │                         │
      │                         └─ RedisFlagSource: cache last-known-good, maxStale, single-flight (FTR-02)
      │
      ├─ MasterSwitch: kill-switch global lido antes de qualquer estratégia
      ├─ Bucketer: hash determinístico (FNV-1a) para PERCENTAGE/VARIANT
      └─ publish ──> DecisionListener[] (MicrometerDecisionListener, LoggingDecisionListener)

FlagAdminService ──delegates──> VersionedFlagStore ──EVAL Lua──> Redis (flag key + audit stream, FTR-04)
      │
      └─ FlagChangeNotifier ──publish──> Redis pubsub ──> FlagChangeSubscriber (reconexão + convergência, FTR-03)
```

`FeatureResolver` é o único ponto de entrada de leitura: aplica exatamente uma estratégia por `FlagType` (`BOOLEAN`/`PERCENTAGE`/`ALLOWLIST`/`VARIANT`), consulta o `MasterSwitch` antes de tudo, e publica a decisão para os `DecisionListener`s registrados (métricas/logs). `FlagAdminService` é o único ponto de entrada de escrita: toda mutação passa por `VersionedFlagStore`, cujo script Lua faz o compare-and-set e grava a entrada de auditoria no mesmo `EVAL` — não há janela em que uma mutação aconteça sem seu registro de auditoria correspondente.

## Fronteiras

- A biblioteca não conhece o fluxo de pagamento nem qualquer outro boundary do workspace (`StandaloneBoundaryTest` garante isso estruturalmente).
- `feature-demo`/`pilot-app` dependem apenas do projeto local `:feature-control` (não do GAV publicado) — a fronteira entre "biblioteca" e "consumidor real" só é exercida pelo `consumer-fixture`, que resolve exclusivamente o artefato publicado.
- Redis é a única dependência externa em runtime: store dinâmico de flags, canal de pubsub de invalidação e stream de auditoria compartilham a mesma instância, sob `key-prefix` configurável.

## Decisões que moldaram o desenho

- **CAS-com-auditoria-atômica em vez de dois passos** (FTR-04): mutação e auditoria no mesmo `EVAL` Lua elimina a janela em que um crash entre os dois deixaria uma mutação não auditada.
- **Cardinalidade e PII bounded por padrão** (FTR-05): `CardinalityGuard` e `SubjectHasher` protegem contra o caso comum de um app consumidor nunca configurar allowlists de tag — o limite é a política padrão, não algo que cada app precisa lembrar de configurar.
- **Exemplos nunca inicializam em produção** (SEC-01/SEC-02) — ver [ADR-0001](adr/0001-nonproduction-example-startup-guard.md).
