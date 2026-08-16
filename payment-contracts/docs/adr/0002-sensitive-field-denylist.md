# ADR-0002: Denylist de campos sensíveis nos modelos de payload

- Status: Accepted
- Date: 2026-08-16

## Contexto

A revisão de arquitetura 2026-08 (`docs/architecture-review-2026-08.md`) apontou risco de dado de
cartão (PAN, CVV/CVC) ou credencial (senha, secret, token) entrar em um payload publicado ou
logado sem revisão manual pegar o campo antes do release. `payment-contracts` é o único ponto por
onde todo campo de payload passa antes de virar contrato Kafka/HTTP, então é o lugar certo para um
gate automático: falhar o build assim que um campo sensível aparecer num modelo, em vez de confiar
em revisão de código.

## Decisão

`SensitiveFieldGuardUnitTest` varre reflexivamente todas as classes de
`com.example.payments.common.model` e falha se o nome de algum campo contiver, sem diferenciar
maiúsculas/minúsculas, um dos termos da denylist: `pan`, `card`, `cvv`, `cvc`, `password`,
`secret`, `token`. O casamento é por substring (`cardNumber`, `panMasked`, `authToken` também
falham), então a lista pega variações de nome, não só o termo exato. A varredura é automática (lê
os arquivos do pacote `model` em tempo de teste) — um modelo novo entra na cobertura sem precisar
editar o teste.

Esta é uma política de forma (nome do campo), não de conteúdo: ela impede que um campo com esse
propósito sequer seja declarado. Mascaramento, criptografia de campo e tokenização real de PAN são
fora do escopo desta ADR (ver Consequências).

## Alternativas consideradas

### Revisão de PR manual com checklist

Rejeitada: depende de disciplina humana em todo PR, para sempre. Falha silenciosa é o modo comum
de falha de checklist.

### Scanner de conteúdo em runtime (regex sobre valores serializados)

Rejeitada por agora: exigiria interceptar toda serialização JSON/Avro e custaria uma dependência de
biblioteca de scanning. O nome do campo já impede o problema na origem (o autor do modelo precisa
escolher deliberadamente burlar o nome), a um custo de implementação muito menor. Fica registrada
como extensão possível se um campo sensível escapar via nome disfarçado.

### Denylist configurável fora do código (arquivo externo)

Rejeitada: a lista muda raramente e junto com o modelo; mantê-la como constante Java ao lado do
teste que a aplica evita um arquivo de configuração extra para uma lista de sete termos.

## Consequências

- todo campo novo em `com.example.payments.common.model` passa pelo guard antes de compilar;
- a lista é um ponto de partida, não uma prova de ausência de dado sensível — nomes que escapam
  completamente do vocabulário da denylist (por exemplo um acrônimo interno não listado) não são
  pegos; a lista evolui conforme o modelo evolui;
- criptografia de campo e mascaramento real de PAN permanecem fora de escopo desta ADR (POC não os
  implementa; ver `docs/architecture-review-2026-08.md`);
- o teste vive em `contract-model` porque é onde os modelos de payload são centralizados; não se
  aplica a `EventEnvelope` (campos técnicos de correlação, sem dado de negócio sensível).

## Supersession

Este ADR não substitui decisão local anterior. Trocar a estratégia de forma (nome) para uma de
conteúdo (scanning de valor serializado) exige novo ADR que indique explicitamente a supersession
deste documento.
