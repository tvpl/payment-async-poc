# Arquitetura

`POST /jobs` reserva idempotência (se a chave vier), persiste status `PROCESSING` e só então publica no Redis Stream (`XADD`) — nessa ordem, para que o polling nunca confunda "aceito, ainda em processamento" com "nunca existiu" (RED-01). O request então tenta um `BRPOP` limitado (pool de conexões dedicado, orçamento medido a partir da aquisição, não só do pop) e responde `200` se o worker liberar a tempo, ou `202` com o `jobId` para polling caso contrário (RED-02).

Cada instância roda `worker-concurrency` workers, cada um com um nome de consumidor único (`<instance-id>-w<index>`) dentro do mesmo consumer group (RED-04). Um único worker por vez segura o turno de reclaim (lease com fencing por dono) e varre o PEL: entradas idle além de `reclaim-idle` são reclamadas e reprocessadas; entradas que já atingiram `max-deliveries` vão para a DLQ. A conexão do worker é (re)estabelecida dentro do laço com backoff exponencial limitado; a readiness só sobe quando um worker de fato lê do grupo (RED-05).

Ao terminar, o worker libera resultado, status terminal e o wakeup (`LPUSH` na lista por requisição) em um único script Lua idempotente, gated por uma chave-marcador — uma redelivery nunca duplica o wakeup nem deixa o status preso em `PROCESSING` com o resultado já pronto (RED-06). O ACK só acontece depois desse script retornar sem lançar. Mensagem inválida (jobId/amount ausente ou malformado) ou que excede `max-deliveries` é gravada na DLQ com o motivo antes do ACK — nunca depois, nunca silenciosamente (RED-07).

O stream nunca é trimado automaticamente; um monitor de retenção só observa e alerta antes do orçamento seguro de backlog (RED-03, ver [ADR-0001](adr/0001-stream-retention-and-wakeup-protocol.md)).

Não há Kafka, PostgreSQL, Apicurio Registry ou dependência de código de outra fronteira do workspace — `StandaloneBoundaryTest` garante isso no build. Redis é a única dependência externa.
