# Guia local para agentes

Estas instruções governam somente `async-redis-service`. Regras cross-boundary permanecem no `AGENTS.md` do workspace.

## Mapa e ownership

- `src/main/.../api`: aceitação do job, idempotência, status consultável, autenticação `X-API-Key` e guarda de produção (RED-01, RED-08).
- `src/main/.../queue`: transporte Redis (Stream + BRPOP) e o pool de espera limitado (RED-02).
- `src/main/.../worker`: identidade de consumer única por processo, reclaim coordenado e loop de conexão com backoff/readiness (RED-04, RED-05).
- `src/main/.../result`: liberação atômica de resultado, status e wakeup via Lua idempotente (RED-06).
- `src/main/.../dlq`: dead-letter durável com motivo, antes do ACK (RED-07).
- `src/main/.../retention`: monitor de retenção do stream — nunca trima, alerta antes do orçamento (RED-03).
- `src/main/.../redis`: conexões compartilhadas, pool de wait e conexões dedicadas de worker.
- `src/main/.../controller`, `dto`, `config`, `metrics`, `ratelimit`: HTTP, configuração tipada, métricas Prometheus e rate limiter de admissão.
- `src/test`: unidade e integração com Redis real (`*IT.java`, requer `-PwithIT`).
- `ops`: alertas e runbooks pertencentes a este serviço.
- `deploy`: gates da imagem e do Compose.
- `docs`: arquitetura, contratos, configuração, segurança, operação e ADRs locais.
- `.github`: pipeline extraível; não publica nem faz deploy.

## Fontes de verdade

1. código, configuração e testes desta raiz;
2. Dockerfile, Compose e scripts executáveis locais;
3. ADRs aceitos e documentação local;
4. decisões cross-boundary em `../.specs/STATE.md` enquanto o workspace existir.

## Invariantes

- Status é persistido antes do enfileiramento; polling nunca confunde "em processamento" com "desconhecido".
- Consumer id é único por instância e worker (`<instance-id>-w<index>`); nenhum processo compartilha nome de consumidor.
- Reclaim tem um único coordenador por vez; workers não competem pelo mesmo scan do PEL.
- Resultado, status terminal e wakeup são liberados atomicamente (script Lua) e de forma idempotente antes do ACK.
- Mensagem inválida (jobId/amount ausente ou malformado) e mensagem que excede `max-deliveries` vão para a DLQ com motivo antes do ACK — nunca ACK silencioso.
- O stream nunca é trimado automaticamente; a política `ACKED` fica registrada como trabalho futuro (ver ADR-0001).
- Pool de espera tem `maxTotal`/`maxWait` finitos; saturação vira `202` com backpressure explícito, nunca bloqueio sem orçamento.
- O Compose cria somente este serviço e usa a rede externa do sandbox.
- A imagem usa UID/GID 10001, filesystem read-only e capabilities removidas.

## Limites de ownership e ações proibidas

- Não crie Redis, observabilidade ou outra infraestrutura neste Compose.
- Não reative `XADD ... MAXLEN` inline sem antes revisar o ADR-0001 e a versão mínima do Redis/Lettuce.
- Não enfraqueça testes, apague cobertura ou confirme (`XACK`) uma mensagem antes que seu efeito (resultado, DLQ) esteja durável.
- Não exponha métricas/detalhes de health anonimamente além do já configurado.
- Não registre segredos, `X-API-Key`, payload sensível ou conteúdo de `.env`.
- Não faça push, deploy ou publicação externa sem autorização específica.

## Gates

```bash
./gradlew test --no-daemon
./gradlew test -PwithIT --no-daemon
./gradlew build --no-daemon
scripts/verify-docs.sh
deploy/verify.sh --structural
git diff --check
```

`deploy/verify.sh` sem `--structural` executa o smoke da imagem e exige Docker e sandbox saudável. Ausência dessas dependências é `NOT_RUN`, nunca aprovação.
