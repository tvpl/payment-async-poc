# ADR-0001: Artefatos de contrato e compatibilidade

- Status: Accepted
- Date: 2026-08-08

## Contexto

O antigo módulo `common` misturava contratos, integração com Registry e rate limiting. Dependências `project()` impediam release e consumo independentes. Schemas evoluíam sem um gate transitive versionado.

## Decisão

Publicaremos dois artefatos: `payment-contract-model` para dados e schemas gerados; `payment-contract-avro-apicurio` para mapper e codec limitado. Schemas atuais ficam em `schemas/`; versões publicadas ficam em histórico append-only.

Toda mudança passa compatibilidade `FULL_TRANSITIVE` local antes da publicação. Produção usa schemas previamente registrados e auto-registration desabilitado. Mudança incompatível cria major, artifact id e tópico novos e coexiste com a versão anterior.

## Alternativas consideradas

### Um único artefato

Rejeitada porque obrigaria consumidores apenas de modelo a carregar integração de Registry e reduziria a clareza de ownership.

### Dependência por source/composite obrigatório

Rejeitada porque mascara o comportamento de repositórios independentes e não prova o POM publicado.

### Compatibilidade BACKWARD

Rejeitada porque consumidores e produtores não migram atomicamente. O fluxo exige leitura segura nos dois sentidos durante coexistência.

## Consequências

- consumidores escolhem explicitamente modelo ou adapter;
- o release publica e testa dois GAVs;
- histórico ocupa espaço, mas torna o gate reproduzível;
- evolução incompatível exige infraestrutura e operação paralelas;
- Registry externo deixa de ser requisito para o dry run de CI.

## Supersession

Este ADR não substitui decisão local anterior. Uma mudança de particionamento ou política de compatibilidade deve criar novo ADR e indicar explicitamente a supersession deste documento.
