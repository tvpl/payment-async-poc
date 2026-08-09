# Outage e recuperação de worker

1. `/health/readiness` fica `DOWN` (indicador `async-redis-workers`) assim que nenhum worker consegue ler do consumer group; `consumingWorkers` nos detalhes mostra quantos ainda sustentam capacidade.
2. Um worker sem Redis reconecta sozinho com backoff exponencial limitado (`connect-backoff-min`/`connect-backoff-max`) — não reinicie o processo por causa disso; verifique se o Redis em si está saudável.
3. Quando o Redis volta, readiness sobe assim que o primeiro worker completa uma leitura real do grupo; jobs enfileirados durante a queda são processados normalmente a partir daí.
4. Duas instâncias nunca compartilham um nome de consumidor (`<instance-id>-w<index>`, RED-04); se `XINFO CONSUMERS` mostrar menos consumidores do que o esperado, confira se `ASYNC_INSTANCE_ID` não foi fixado igual em duas réplicas.
5. Escale se `consumingWorkers` ficar em zero por mais tempo do que o SLA de recuperação combinado, mesmo com o Redis saudável — nesse caso o problema é do processo, não da rede.
