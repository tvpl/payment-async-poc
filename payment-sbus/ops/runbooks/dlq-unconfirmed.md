# DLQ não confirmada

1. Verifique `sbus_dlq_unconfirmed`, idade mais antiga e falhas de publish.
2. Confirme Kafka e o tópico `payment.simulation.dlq`; não altere `DLQ_PENDING` manualmente.
3. Após recuperar o broker, observe reclaim, republicação e transição para `DLQ_PUBLISHED` somente depois do ack.
4. Correlacione duplicatas por deduplication key, request id e coordenadas de origem.
5. Escale enquanto qualquer item permanecer antigo; silêncio do alerta não substitui contagem zero.
