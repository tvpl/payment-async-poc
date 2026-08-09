# ADR-0001 — Espera síncrona sobre fluxo assíncrono, com recuperação na reserva de idempotência

Status: Accepted
Data: 2026-08-09

## Contexto

O cliente HTTP quer uma resposta de pagamento. O processamento é assíncrono: a API publica um
evento no Kafka, o SBUS coordena, o Core decide, e o resultado volta por outro tópico. Entre a
publicação e o resultado existem quatro janelas em que algo pode falhar: a publicação pode ser
recusada, o processo pode morrer no meio dela, a espera pode estourar, e o resultado pode chegar em
uma instância diferente daquela que atende o cliente.

Duas exigências entram em conflito direto. A identidade da requisição precisa ser estável para que
um retry não vire uma segunda cobrança, o que empurra para reservar a identidade **antes** de
publicar. E a API não pode afirmar que existe trabalho em andamento quando não existe, o que
proíbe deixar uma reserva parada aparentando processamento até o TTL vencer.

## Decisão

A API bloqueia a requisição HTTP por um orçamento configurado e devolve o resultado ou `202`.

O estado da publicação fica na **reserva de idempotência**, não na entrada de status. A reserva
guarda `{requestId, fingerprint, publishState, publishLeaseExpiresAt}` em uma única operação
atômica, e é marcada `PUBLISHED` apenas depois do ack do broker ou `PUBLISH_FAILED` quando o envio
falha. Um retry com a mesma chave e o mesmo payload cujo publish nunca foi confirmado retoma o
**mesmo** `requestId` em vez de esperar a reserva expirar. Enquanto uma tentativa mantém seu lease,
uma duplicata concorrente recebe replay em vez de publicar a mesma identidade duas vezes.

Sem status conhecido, a resposta é `TIMEOUT` — uma afirmação sobre esta chamada — nunca
`PROCESSING`, que seria uma afirmação sobre o Core.

## Alternativas consideradas

**Retornar `202` sempre e nunca esperar.** Mais simples e sem nenhuma das janelas acima, mas
transfere a complexidade de polling para todo cliente e desperdiça a latência baixa do caminho
feliz, em que o resultado chega em milissegundos.

**Guardar o estado de publicação na entrada de status.** Foi descartado porque o status expira
antes ou junto com a reserva. Um retry encontraria "sem status" e não teria como distinguir "nunca
publicado" de "publicado, status já expirado" — e republicaria um pagamento concluído.

**Acrescentar `PUBLISH_FAILED` a `SimulationStatus`.** O enum pertence ao artefato publicado
`payment-contracts`, consumido por outras fronteiras. Um estado que só a API entende não pertence a
um contrato compartilhado, e a mudança exigiria versionar e republicar o contrato.

**Liberar a reserva quando a publicação falha.** Resolveria o órfão, mas o retry geraria um
`requestId` novo, que é exatamente a duplicação de identidade que a idempotência existe para
impedir.

## Consequências

Um crash entre o ack do Kafka e a marcação da reserva faz o retry republicar a mesma identidade.
Isso é aceito de forma explícita: PAY-06 exige que o consumidor a jusante absorva a repetição sem
alterar o desfecho já escolhido, e o consumer desta fronteira também preserva o terminal existente.
Preferimos uma repetição que o downstream sabe tratar a uma janela em que o pagamento se perde.

`instances` e `publish-lease` passam a ser configuração operacional com efeito de correção, não
apenas de tuning: um lease curto demais permite retomada concorrente, e um `instances` menor que a
frota afrouxa a admissão degradada.

A espera bloqueante só é viável sobre virtual threads. Em um runtime sem Loom, essa decisão precisa
ser revista.

## Supersession

Nenhuma. Esta é a decisão vigente para o modelo de espera e recuperação do `payment-api`.
