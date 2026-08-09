# Retry backlog

1. Confirme consumer lag, linhas `PENDING/RETRY_PENDING`, idade e `next_attempt_at`.
2. Identifique a dependência falha sem inspecionar payload sensível.
3. Restaure a dependência e acompanhe taxa de drain; não antecipe `not-before`.
4. Se a retenção se aproximar do limite, bloqueie nova admissão no owner da API e escale.
5. Encerre somente quando backlog, lag e falhas retornarem ao baseline e não houver estado órfão.
