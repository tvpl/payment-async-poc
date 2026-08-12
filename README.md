# Payment Async Workspace

Este repositório é um workspace de sete fronteiras autossuficientes. A raiz é um mapa de workspace e um ponto de integração. Ela não é documentação de produto nem representa um único deploy.

O estado atual continua sendo uma PoC. Nenhum componente deve ser tratado como pronto para produção sem os gates e relatórios datados definidos na [especificação de segregação](.specs/features/repository-segregation-production-hardening/spec.md).

## Fronteiras

| Fronteira | Responsabilidade | Status |
| --- | --- | --- |
| `payment-contracts` | contratos e compatibilidade de eventos | produtiva |
| `payment-api` | interface HTTP e coordenação da resposta | produtiva |
| `payment-sbus` | processamento durável e integração com o Core | produtiva |
| `payment-core-mock` | simulador determinístico do Core | `NON_PRODUCTION` |
| `feature-control` | biblioteca de controle de features e exemplos | produtiva (exemplos `NON_PRODUCTION`) |
| `async-redis-service` | exemplo async-to-sync baseado em Redis | produtiva |
| `sandbox` | infraestrutura local compartilhada e observabilidade | infraestrutura local |

Cada fronteira tem build, documentação, CI e instruções de IA locais. Aplicações têm também imagem e Compose próprios. `payment-contracts` e `feature-control` são publicadas como artefatos Maven e não recebem containers artificiais.

## Regras do workspace

- Dependências cross-boundary usam coordenadas Maven versionadas. Composite Gradle é uma conveniência local explícita, nunca um requisito de release.
- Somente `sandbox` cria Kafka, Redis, PostgreSQL, Schema Registry e observabilidade local. Composes de aplicação conectam à rede externa do sandbox.
- Contratos HTTP, Kafka, Avro e migrations permanecem compatíveis entre versões sem uma decisão registrada.
- `payment-core-mock`, `feature-demo` e `pilot-app` são `NON_PRODUCTION`.

As decisões transversais estão em [.specs/STATE.md](.specs/STATE.md). O plano de segregação está em [design.md](.specs/features/repository-segregation-production-hardening/design.md) e [tasks.md](.specs/features/repository-segregation-production-hardening/tasks.md).

## Gates

Cada raiz standalone roda seu próprio wrapper Gradle:

```bash
cd <fronteira> && ./gradlew build --no-daemon
cd <fronteira> && ./gradlew test -PwithIT --no-daemon
```

Gates cross-boundary, a partir da raiz do workspace:

```bash
scripts/verify-workspace.sh
python3 scripts/equivalence/equivalence.py verify --root . --manifest scripts/equivalence/baseline-manifest.json
python3 scripts/docs/validate_docs.py
python3 scripts/workspace/check_root_governance.py
```

O inventário histórico e seu uso estão documentados no [gate de equivalência](scripts/equivalence/README.md). Ele compara o conteúdo atual das sete raízes contra um snapshot congelado anterior à segregação; qualquer divergência real precisa de destino explícito e reconciliação, nunca de um baseline reescrito para esconder perda.

## Documentação

A documentação de workspace vive em [docs](docs/workspace-overview.md): visão geral, arquitetura cross-boundary, fluxo de pagamento ponta a ponta, ownership de dados, contratos de resiliência entre fronteiras, política de tecnologia e política de testes compartilhada. Documentação específica de cada fronteira (arquitetura interna, contratos, configuração, segurança, operação, observabilidade) vive no `docs/` local dessa fronteira.

Agentes de IA devem começar por [AGENTS.md](AGENTS.md) e seguir primeiro o `AGENTS.md` local de cada fronteira.
