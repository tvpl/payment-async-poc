# ADR-0001 — Simulador determinístico permanentemente NON_PRODUCTION

Status: Accepted
Date: 2026-08-09

## Contexto

Testes do fluxo assíncrono precisam de respostas aprovadas, recusadas, lentas e transitoriamente falhas sem depender de um ambiente externo. Um mock aleatório torna redelivery e incidentes difíceis de reproduzir; promover o mock cria falsa equivalência operacional e financeira.

## Decisão

Manter `payment-core-mock` como fronteira extraível, porém permanentemente `NON_PRODUCTION`. A classificação aparece no startup, imagem, README e CI. Outcome, latência e autorização derivam de SHA-256 sobre `CORE_SEED` + `requestId`; configurações inválidas recusam startup. O processo consome contratos publicados e conecta somente à infraestrutura externa do sandbox.

## Alternativas consideradas

1. Aleatoriedade por execução: rejeitada porque duplicata e redelivery poderiam divergir.
2. Fixtures estáticas por request id: rejeitadas porque exigiriam estado/gestão e cobriram menos combinações.
3. Usar uma integração externa em todo teste: rejeitada por custo, disponibilidade, dados e perda de isolamento.
4. Permitir promoção por profile: rejeitada porque um profile não acrescenta semântica, segurança, SLA ou operação necessários.

## Consequências

- cenários e falhas são reproduzíveis por seed/request id;
- a fronteira pode evoluir e ser testada sem acoplar builds por source path;
- redelivery falho bloqueia a partição em vez de avançar silenciosamente;
- throughput e regras simuladas não são evidência de capacidade ou comportamento externo;
- documentação e CI devem falhar se qualquer superfície obrigatória perder a classificação.

## Supersession

Nenhum ADR substitui esta decisão. Uma integração com finalidade produtiva exige outro owner, requisitos, threat model, operação, testes de capacidade e ADR próprio; não é uma evolução deste simulador.
