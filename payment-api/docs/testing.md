# Testes

Os testes são a especificação executável desta fronteira. Nenhum gate passa por inspeção.

## Gate rápido

```bash
./gradlew test --no-daemon
```

Unitários apenas (arquivos `*IT` são excluídos sem `-PwithIT`). Cobre fingerprint, guards de
configuração, ciclo de vida do waiter, política de circuito do fallback, classificação de falhas do
consumer e orçamento degradado do limitador.

## Gate de integração

```bash
export API_DEV_JWT_SECRET=... PAYMENT_API_KEY=...
./gradlew test -PwithIT --no-daemon
```

Precisa de Docker: Kafka, Redis e Apicurio sobem via Testcontainers. Cobre o fluxo fim a fim,
matriz de segurança e management, idempotência contra Redis real, recuperação de falha de
publicação com broker derrubado, orçamento do fallback contra um SBUS lento de verdade, poison/DLQ
e outage de Redis no consumo, e admissão `202`/`429` por recurso e por tenant.

Dois ITs param containers de propósito (`AdmissionRedisOutageIT`, `ResponseConsumerRedisOutageIT`,
`PublishFailureIT`) e por isso vivem em classes próprias, com containers próprios.

## Documentação e imagem

```bash
scripts/verify-docs.sh          # pacote completo, links e conteúdo obrigatório
deploy/verify.sh --structural   # Dockerfile, Compose, CI e runbooks
deploy/verify.sh                # smoke de runtime: exige Docker e a rede do sandbox
./gradlew build --no-daemon     # build + distribuição
```

`scripts/test_docs.py` inclui testes negativos: um pacote ausente e um link quebrado precisam
falhar, senão o validador não discrimina nada.

## Estado atual

| Gate | Resultado |
| --- | --- |
| Rápido | PASS |
| Integração | PASS |
| Build | PASS |
| Documentação | PASS |
| Pacote de release (estrutural) | PASS |
| Pacote de release (smoke de runtime) | `NOT_RUN` |
| Carga | `NOT_RUN` — ver [performance.md](performance.md) |
