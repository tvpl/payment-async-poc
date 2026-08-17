# Pool PostgreSQL saturado

1. Verifique `hikaricp_connections_active`, `hikaricp_connections_pending` e `hikaricp_connections_idle`; compare `active` com `hikaricp_connections_max`.
2. Cheque o indicator `postgresql-pool` em `/health` (aquisição com timeout curto) e o indicator `postgresql` — um pool saturado com PostgreSQL saudável aponta para conexão presa em I/O, não para o banco em si.
3. Procure transação longa ou conexão que nunca fechou (query em andamento há muito tempo, lock não liberado).
4. Se o pool estiver genuinamente pequeno para a carga, ajuste `datasources.default.maximum-pool-size`; não aumente sem entender a causa da saturação.
5. Encerre somente quando `hikaricp_connections_pending` voltar a zero de forma sustentada.
