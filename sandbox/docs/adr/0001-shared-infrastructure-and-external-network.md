# ADR-0001: infraestrutura compartilhada e rede externa

- Status: Accepted
- Date: 2026-08-08

## Contexto

O Compose legado misturava aplicações, bancos, mensageria, Registry e observabilidade. Isso duplicaria infraestrutura ao extrair repositórios e impediria que uma aplicação fosse iniciada/escalada de forma independente.

## Decisão

O sandbox será o único owner da infraestrutura local compartilhada. Seu Compose mínimo cria a rede nomeada `payment-sandbox`. Composes de aplicação declararão essa rede como externa e usarão DNS de serviço. Observabilidade e ferramentas ficam em overlay/profiles opcionais. Assets de produto continuam no owner e entram somente por manifest versionado.

## Alternativas

1. Manter um Compose central com todas as aplicações: rejeitada porque preserva acoplamento de build e release.
2. Cada aplicação criar Kafka, Redis, PostgreSQL e observabilidade: rejeitada por duplicar estado, recursos e operação.
3. Usar somente serviços host sem rede Compose: rejeitada por perder DNS e startup reproduzível.

## Consequências

- Aplicações falham claramente se a rede externa ainda não existir.
- O sandbox pode evoluir e ser operado sem fonte de aplicação.
- E2E cross-boundary exige iniciar sandbox e aplicações separadamente.
- Registry em memória continua efêmero; contratos devem ser republicáveis pelo owner.
- Rede compartilhada aumenta alcance local entre containers e não representa isolamento produtivo.

## Supersession

Substitui o ownership implícito do Compose legado. Uma solução local diferente deverá criar novo ADR e indicar explicitamente a supersession deste documento.
