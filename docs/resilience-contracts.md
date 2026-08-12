# Contratos de resiliência entre fronteiras

Mecanismos de resiliência que atravessam mais de uma fronteira, e os trade-offs que motivaram cada escolha. Resiliência interna de uma fronteira (retry local, circuito, timeout de um único cliente) pertence ao `architecture.md` daquela fronteira, não a este documento.

## Pontos de resiliência

| Mecanismo | Garante | Fronteira dona |
|---|---|---|
| Outbox (publica fora da TX, claim/lease) | Publicação confiável; sem dual-write | `payment-sbus` |
| Reaper da outbox | Recupera linhas presas em `IN_PROGRESS` quando o publicador cai | `payment-sbus` |
| Housekeeping da outbox | Tabela da outbox não cresce indefinidamente | `payment-sbus` |
| Consumers síncronos por registro, com retry topics e DLQ | Zero perda silenciosa; retries fora da partição principal | `payment-sbus`, `payment-api` |
| Serialização fora da transação | Não segura conexão de DB durante I/O do schema registry | `payment-sbus` |
| Idempotência (3 camadas) | Reprocessamento sem efeito duplicado | `payment-api` (Redis `idem:`) + `payment-sbus` (`request_id` UNIQUE, `idempotency_record`) |
| `FOR UPDATE SKIP LOCKED` | Múltiplas instâncias publicam sem duplicar | `payment-sbus` |
| Rate limiter distribuído (Core) | Limite global protege o Core | `payment-sbus` |
| Rate limiter distribuído (admissão, 429) | Admissão global; virtual threads não limitam carga sozinhas | `payment-api` |
| AuthN por API key (401) | Endpoints de negócio protegidos | `payment-api` |
| Retenção/housekeeping | Tabelas (`idempotency_record`, `payment_sbus_message`) limitadas | `payment-sbus` |
| GET com fallback durável | Resultado nunca se perde por TTL ou troca de instância | `payment-api` consultando `payment-sbus` |
| Redis lazy + resubscribe | Sobe com Redis fora; pub/sub se reinscreve sozinho | `payment-api` |
| read-after-register | Cobre resposta ultrarrápida ou replay | `payment-api` |
| Shutdown gracioso | Waiters liberados; nenhuma conexão fica pendurada | `payment-api` |
| Producer idempotente (`acks=all`, `enable.idempotence`) | Sem duplicatas por retry do producer | `payment-api`, `payment-sbus` |
| Timeout obrigatório na espera HTTP | Nunca segura conexão indefinidamente | `payment-api` |

Detalhe de implementação de cada mecanismo está no `architecture.md` da fronteira dona.

## Trade-offs entre fronteiras

### Esperar evento (200) vs responder 202 imediato

Esperar dá melhor UX quando o Core é rápido, mas ocupa uma virtual thread por requisição em `payment-api`. Responder `202` de imediato escala melhor sob carga, mas exige polling do cliente. A escolha do workspace é híbrida: espera curta com timeout, cai para `202`. Equilibra UX e escala.

### Redis (correlação) vs memória local

Correlação em memória local é simples, mas quebra com múltiplas instâncias de `payment-api`: o evento de resultado pode chegar em outra instância. Redis com pub/sub funciona horizontalmente; o custo é uma dependência extra e latência de rede.

### Kafka como buffer/backpressure

Absorve rajada entre `payment-api` e `payment-sbus`, desacopla cadências, garante at-least-once. O contra é eventual consistency e a operação de um cluster.

### Outbox no payment-sbus

Resolve dual-write e mantém o Core (`payment-core-mock`) agnóstico do mecanismo de publicação. O contra é a tabela crescer (mitigado por housekeeping) e uma latência extra de polling.

### Limites das virtual threads

Virtual threads são baratas para I/O, mas não dão backpressure nem limitam carga: por isso o rate limit explícito continua necessário nas duas fronteiras. Cuidado com pinning (`synchronized`/JNI); o serializador Avro compartilhado (`payment-contracts`) usa instâncias `ThreadLocal` para evitar lock global e pinning no encode/decode.

### Idempotência e reprocessamento

At-least-once exige consumidores idempotentes. O workspace usa chaves e estados terminais em duas fronteiras (`payment-api` e `payment-sbus`) para evitar efeito colateral duplicado, mesmo em redelivery cross-boundary.

### Operação com múltiplas instâncias

`payment-sbus`: `SKIP LOCKED` permite N publicadores concorrentes sem colidir. `payment-api`: consumer group único e estável por deployment, mais Redis pub/sub para acordar o waiter na instância certa.

## Próximas evoluções sugeridas

- Schema Registry com regra de compatibilidade explícita (por exemplo `BACKWARD`) e testes de contrato, ver [payment-contracts/docs/contracts.md](../payment-contracts/docs/contracts.md).
- Particionamento/arquivamento da outbox para volumes altos.
- Circuit breaker no gateway do Core quando ele deixar de ser mediado por evento (HTTP/gRPC síncrono).
- JWT/OAuth2 mais mTLS no lugar da API key; TLS/SASL no Kafka.

Checklist completo de produção em [Evidências de produção](production-evidence.md).

## Ver também
- [Evidências de produção](production-evidence.md) · [Fluxo de pagamento](payment-flow.md) · [Política de tecnologia](technology-policy.md)
