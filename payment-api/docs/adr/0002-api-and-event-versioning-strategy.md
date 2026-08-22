# ADR-0002 — Estratégia de versionamento de API e de eventos

Status: Accepted
Data: 2026-08-21

## Contexto

O workspace já tinha duas superfícies versionáveis crescendo sem uma regra
comum: a rota HTTP pública (`/payment-simulations` estável e `/v0/payment-simulations`
como major experimental, gated por `feature-control`) e o envelope de eventos
Kafka (`eventVersion`, hoje `"1.0"`). A auditoria arquitetural 2026-08 apontou
a ausência de uma decisão registrada como lacuna (API-01): sem uma regra
explícita, cada nova major corre o risco de escolher um esquema de path/header
diferente do anterior, e cada consumer pode reagir de um jeito diferente a um
`eventVersion` que não reconhece.

## Decisão

**HTTP**: o path carrega o major somente quando ele diverge do major estável
corrente. Hoje isso significa `/payment-simulations` sem prefixo é a major
estável implícita, e `/v0/payment-simulations` é uma major experimental
anterior (gated pela flag `payment-api-v0` em `feature-control`; invisível via
`404` para quem não é elegível — nunca `401`/`403`, que revelariam sua
existência). Toda rota que **não** for a major estável implícita emite
`X-Api-Version` na resposta identificando seu major (`/v0` já emite hoje). Uma
futura major além da estável corrente (por exemplo, quando `v1` deixar de ser
implícita e `/v2` for introduzida) segue a mesma regra: a nova major ganha
path e header explícitos, e o path antigo continua servindo sua major original
até ser desativado por decisão própria, documentada separadamente.

**Eventos**: `eventVersion` é uma string `major.minor` (hoje `"1.0"`,
`EventEnvelope.CURRENT_VERSION`). Mudança aditiva/compatível incrementa
`minor`; mudança que quebra compatibilidade incrementa `major`. Todo consumer
que decodifica o envelope chama `EnvelopeVersions.assertKnownMajor(eventVersion)`
antes de processar; um major desconhecido lança `UnknownEventMajorException`,
que o consumer trata como **poison** — vai direto para DLQ com razão
explícita, nunca é reprocessado como falha transiente nem ignorado em
silêncio (API-02, já coberto por `PaymentResponseConsumer` no `payment-api` e
`SimulationMessageHandler` no `payment-sbus`).

## Alternativas consideradas

**Header de versão em toda rota, inclusive a estável.** Cobriria o caso de um
proxy/log que só inspeciona headers, mas duplica a informação que o próprio
path já carrega para o caso comum e não muda nenhum comportamento observável
hoje — se um consumidor de log precisar disso, é mais barato adicionar então
do que manter uma emissão redundante em toda resposta desde já.

**`eventVersion` numérico único (sem separação major/minor).** Perderia a
distinção entre mudança compatível e mudança que quebra o consumer — toda
mudança viraria, na prática, uma major, inflando o número de majors vivos que
os consumers precisam reconhecer.

**Consumer descarta silenciosamente (ou loga e segue) um major desconhecido.**
É exatamente o "processar às cegas" que API-02 proíbe: um evento de uma major
que o consumer não entende pode ter um payload semanticamente incompatível; a
única resposta segura é poison para DLQ, nunca uma tentativa de interpretar
campos que podem não significar o que o consumer assume.

## Consequências

- Introduzir uma nova major HTTP (`/v2`) é aditivo: a rota antiga continua
  respondendo sua major como sempre respondeu, sem migração forçada de
  clientes existentes.
- Introduzir uma nova major de evento exige atualizar
  `EnvelopeVersions`/`assertKnownMajor` em **todo** consumer antes do deploy
  que passa a publicá-la — um esquecimento aqui vira poison em produção, o que
  é o comportamento correto (falha visível), não um bug a evitar.
- A rota estável implícita não precisa de teste de contrato sobre o header
  `X-Api-Version` porque ela não o emite por definição; `/v0` já tem essa
  cobertura (`V0PaymentSimulationController`).

## Supersession

Nenhuma. Esta é a decisão vigente para versionamento de API HTTP e de
eventos no workspace.
