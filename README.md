# Payment Async Workspace

Este repositório está em migração para fronteiras autossuficientes. A raiz é um mapa de workspace e um ponto de integração. Ela não é documentação de produto nem representa um único deploy.

O estado atual continua sendo uma PoC. Nenhum componente deve ser tratado como pronto para produção sem os gates e relatórios datados definidos na [especificação de segregação](.specs/features/repository-segregation-production-hardening/spec.md).

## Fronteiras

| Fronteira de destino | Responsabilidade | Localização transitória | Status |
| --- | --- | --- | --- |
| `payment-contracts` | contratos e compatibilidade de eventos | `common` | planejada |
| `payment-api` | interface HTTP e coordenação da resposta | `api-service` | planejada |
| `payment-sbus` | processamento durável e integração com o Core | `sbus-service` | planejada |
| `payment-core-mock` | simulador determinístico do Core | `core-mock` | planejada, `NON_PRODUCTION` |
| `feature-control` | biblioteca de controle de features e exemplos | `feature-control`, `feature-demo`, `pilot-app` | em migração |
| `async-redis-service` | exemplo async-to-sync baseado em Redis | `async-redis-service` | em migração |
| `sandbox` | infraestrutura local compartilhada e observabilidade | Compose e diretórios de infraestrutura da raiz | planejada |

Cada fronteira terá build, documentação, CI e instruções de IA locais. Aplicações terão também imagem e Compose próprios. Bibliotecas serão publicadas como artefatos Maven e não receberão containers artificiais.

## Regras do workspace

- Dependências cross-boundary usam coordenadas Maven versionadas. Composite Gradle será uma conveniência local explícita.
- Somente `sandbox` criará Kafka, Redis, PostgreSQL, Schema Registry e observabilidade local.
- Contratos HTTP, Kafka, Avro e migrations permanecem compatíveis durante a migração.
- A localização antiga só é removida depois de equivalência funcional, documental e operacional.
- `payment-core-mock`, `feature-demo` e `pilot-app` são `NON_PRODUCTION`.

As decisões transversais estão em [.specs/STATE.md](.specs/STATE.md). O plano aprovado está em [design.md](.specs/features/repository-segregation-production-hardening/design.md) e [tasks.md](.specs/features/repository-segregation-production-hardening/tasks.md).

## Gates atuais

Enquanto as raízes standalone não existem, o build legado permanece como gate transitório:

```bash
./gradlew test --no-daemon
./gradlew build --no-daemon
docker compose config -q
python3 scripts/workspace/check_root_governance.py
```

O inventário histórico e seu uso estão documentados no [gate de equivalência](scripts/equivalence/README.md). Durante a migração, seu diagnóstico permanece vermelho para mudanças ainda não reconciliadas; o gate final exige que toda divergência tenha destino e equivalência provados. Os gates standalone substituirão os comandos Gradle da raiz por fronteira, sem transformar a raiz em novo build agregador.

## Documentação durante a migração

O diretório [docs](docs/README.md) ainda é legado e pode conter claims não certificados. Ele será realocado por owner com manifest explícito. Até essa etapa, código, testes e configuração executável prevalecem sobre o texto.

Agentes de IA devem começar por [AGENTS.md](AGENTS.md) e, quando uma fronteira já tiver seu próprio `AGENTS.md`, seguir primeiro as instruções locais.
