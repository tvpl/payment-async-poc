# Rollback de release

1. Interrompa a promoção e preserve imagem, logs e métricas da versão falha.
2. Reimplante a imagem anterior compatível sem reverter migrations Flyway.
3. Confirme readiness, consumo, outbox, retry e DLQ antes de reabrir admissão.
4. Compare terminais e duplicatas; o primeiro terminal permanece válido.
5. Registre versão, motivo, janela, backlog e tempo de recuperação no relatório da release.
