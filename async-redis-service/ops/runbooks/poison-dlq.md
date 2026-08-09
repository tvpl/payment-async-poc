# Poison e DLQ

1. Leia o stream de DLQ (`async.jobs.dlq` por padrão) e o campo `dlqReason` de cada entrada: `missing-job-id`, `missing-amount`, `invalid-amount`, `negative-amount` (payload malformado, detectado na primeira entrega) ou `max-deliveries-exceeded` (falhou repetidamente até o limite).
2. Não confirme (`XACK`) nem edite uma entrada da DLQ manualmente; ela já chegou lá depois do próprio ACK do item original ter sido feito com segurança (a escrita na DLQ acontece antes do ACK — RED-07).
3. Para `max-deliveries-exceeded`, correlacione com os logs do worker (`processing failed for job ...`) para achar a causa raiz antes de reprocessar.
4. Reprocessamento é manual e deliberado: publique um novo job equivalente pela API; não reinjete o item da DLQ diretamente no stream principal sem entender por que ele falhou.
5. Se a própria escrita na DLQ estiver falhando (ex.: chave de tipo errado, Redis fora), o item original fica pendente no PEL até a DLQ voltar — ele nunca é confirmado sem confirmação da DLQ. Corrija a causa e ele drena sozinho no próximo ciclo de reclaim.
