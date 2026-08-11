# Guia local para agentes

Estas instruções governam somente `feature-control`. Regras cross-boundary permanecem no `AGENTS.md` do workspace.

## Mapa e ownership

- `library/src/main/.../model`: `FlagDefinition`/`Variant`/`FlagType` — o choke point de validação (construtor compacto, FTR-01) usado por YAML, Redis e o path de escrita admin.
- `library/src/main/.../resolver`: `FeatureResolver`/`MasterSwitch` — estratégia única por tipo de flag, kill-switch global.
- `library/src/main/.../store`: `RedisFlagSource` (cache last-known-good, FTR-02), `VersionedFlagStore` (CAS + auditoria atômica, FTR-04), `FlagChangeNotifier`/`FlagChangeSubscriber`.
- `library/src/main/.../pubsub`: reconexão com backoff/jitter e medição de convergência multi-instância (FTR-03).
- `library/src/main/.../admin`: `FlagAdminService`/`FlagConflictException`/`AuditService` — path de escrita autenticado.
- `library/src/main/.../metrics`: `MicrometerDecisionListener`/`LoggingDecisionListener`, `CardinalityGuard`/`SubjectHasher` (bound de cardinalidade e PII, FTR-05).
- `library/src/main/.../bucketing`, `context`, `config`, `spi`: bucketing determinístico, contexto de avaliação, `FeatureSettings` tipado, interfaces de extensão.
- `library/src/test`: unidade e integração com Redis real (`*IT.java`, requer `-PwithIT`); baseline mínimo de 31 testes nunca diminui silenciosamente.
- `examples/feature-demo`, `examples/pilot-app`: apps `NON_PRODUCTION` (AD-005) — recusam boot sob `prod` (`NonProductionExampleGuard`, ver ADR-0001). Dependem apenas do projeto local `:feature-control`; não publicam nada.
- `consumer-fixture`: build Gradle standalone que resolve somente o GAV publicado (`exclusiveContent`); certifica que a biblioteca é consumível de fora do monorepo.
- `scripts`: `check_consumer_fixture.py`, `verify_api_surface.py` (diff de superfície pública via `javap`), `validate_docs.py`, e os wrappers `verify-*.sh`.
- `docs`: arquitetura, configuração, segurança, operação, testes e ADRs locais.
- `.github`: pipeline extraível; não publica nem faz deploy.

## Fontes de verdade

1. código, configuração e testes desta raiz;
2. `build.gradle`/`settings.gradle` e scripts executáveis locais;
3. ADRs aceitos e documentação local;
4. decisões cross-boundary em `../.specs/STATE.md` enquanto o workspace existir.

## Invariantes

- `FlagDefinition`'s construtor compacto é o único ponto de validação; YAML, Redis e o path admin passam todos por ele.
- Toda mutação admin (create/update/delete) é CAS e audita before/after/ator/versão/resultado no mesmo `EVAL` Lua — nunca há mutação sem entrada de auditoria correspondente.
- Toda mutação admin exige um `actor` autenticado não vazio; `delete` sem `?version=` explícito lê a versão atual do store autoritativo (`FlagAdminService.currentVersion`), nunca do resolver cacheado.
- Cardinalidade de tag de métrica (`flag`, `variant`) é bounded por `CardinalityGuard`; o bucketing key nunca aparece em texto puro em log — sempre via `SubjectHasher`.
- `feature-demo`/`pilot-app` nunca inicializam sob o environment `prod` — `NonProductionExampleGuard` falha o startup incondicionalmente.
- O fixture (`consumer-fixture`) nunca lê fonte via `project(...)`; resolve somente o GAV publicado.
- A superfície pública de API promovida (`consumer-fixture/api-surface-baseline.txt`) não perde um membro sem que isso seja um SPEC_DEVIATION documentado — regenerar a baseline (`verify_api_surface.py --write-baseline`) é uma decisão deliberada, não um efeito colateral.

## Limites de ownership e ações proibidas

- Não adicione `project(...)` ou `includeBuild` no `consumer-fixture` nem em `feature-demo`/`pilot-app` apontando para fora deste boundary.
- Não remova/enfraqueça `NonProductionExampleGuard` nem exponha rota de `feature-demo`/`pilot-app` sob `prod` sem revisar SEC-01/SEC-02 e o ADR-0001.
- Não registre `bucketingKey`/userId bruto em log ou métrica; use `SubjectHasher`/`CardinalityGuard`.
- Não enfraqueça testes, apague cobertura, nem regenere `api-surface-baseline.txt` para esconder uma quebra real.
- Não faça push, deploy ou publicação externa sem autorização específica.

## Gates

```bash
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
./gradlew build -PwithIT --no-daemon
scripts/verify-docs.sh
bash scripts/verify-consumer-fixture.sh
git diff --check
```

`scripts/verify-consumer-fixture.sh` exige Redis local (para o gate `-PwithIT` que o antecede logicamente) e publica a biblioteca em um repositório Maven local temporário — nenhuma etapa depende de rede externa nem de Docker.
