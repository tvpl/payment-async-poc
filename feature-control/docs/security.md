# Segurança

## Mutações admin (FTR-04)

`FlagAdminService.put`/`delete` exigem um `actor` autenticado não vazio antes de qualquer chamada ao Redis (`requireActor`, curto-circuita sem tocar o store). Toda mutação aceita é CAS e audita before/after/ator/versão/resultado no mesmo `EVAL` Lua — não existe caminho para uma mutação sem auditoria correspondente. `FeatureAdminController` (no exemplo `feature-demo`) exige `ROLE_ADMIN` via `intercept-url-map` e lê a versão para um `delete` sem `?version=` explícito do store autoritativo (`FlagAdminService.currentVersion`), nunca do resolver cacheado — um bug real de constructor ambíguo que fazia esse valor voltar sempre `0` foi encontrado e corrigido durante o gate de T50 (ver `tasks.md`).

## Telemetria sem PII (FTR-05)

`LoggingDecisionListener` nunca grava o `bucketingKey` (um userId real) em texto puro — `SubjectHasher` produz um token SHA-256 truncado, determinístico (permite correlação) e irreversível. `MicrometerDecisionListener` limita a cardinalidade de `flag`/`variant` via `CardinalityGuard` (`platform.features.metric-cardinality-limit`, padrão 200): valores além do limite colapsam para a série compartilhada `"other"`.

## Exemplos NON_PRODUCTION (SEC-01, SEC-02)

`feature-demo` e `pilot-app` são exemplos de adoção, não serviços produtivos (AD-005). Cada um carrega um `NonProductionExampleGuard` (`@Context @Requires(env = "prod")`) que lança `ConfigurationException` na inicialização — o app inteiro se recusa a subir sob o environment `prod`, o que é estritamente mais forte do que excluir rotas individuais do bean graph: nada inicializa, então nenhuma rota demo/admin pode aparecer em produção. Ver [ADR-0001](adr/0001-nonproduction-example-startup-guard.md) para a decisão e alternativas consideradas. `NonProductionGuardIT` (3 testes por exemplo, 6 no total) prova: recusa sob `prod` sozinho, recusa sob `prod` combinado com outro environment, e sucesso normal sob um environment não-`prod` com a mesma configuração (controle de regressão).

`DevTokenController` (emissor de JWT de desenvolvimento em `feature-demo`) segue `Not for production` na documentação da classe; o guard de startup torna essa garantia estrutural, não apenas documental.

## Consumer fixture (FTR-06)

`consumer-fixture` nunca lê fonte cross-boundary (`check_consumer_fixture.py` rejeita `project(...)`/`includeBuild`/caminhos `../library`); resolve exclusivamente o GAV publicado via `exclusiveContent`. Isso é uma garantia de cadeia de suprimento tanto quanto de compatibilidade: um app externo que declara a mesma dependência recebe exatamente o artefato publicado, nunca uma substituição de código-fonte local.
