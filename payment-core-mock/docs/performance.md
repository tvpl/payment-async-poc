# Performance

## Escopo da garantia

Não existe certificação de throughput ou latência de produção nesta fronteira `NON_PRODUCTION`. Os números configurados são atrasos artificiais e não modelam rede, processamento, disponibilidade ou limites de uma dependência externa.

## Modelo atual

Cada comando bloqueia a thread do listener pelo atraso determinístico. Portanto, throughput depende de partições, concorrência do consumer, latência configurada, Kafka e host local. Um registro falho bloqueia a partição por design para não confirmar poison silenciosamente.

## Uso em testes

- fixe `CORE_SEED` para reproduzir a distribuição;
- registre partições, concorrência, percentuais e bounds junto ao resultado;
- aqueça a JVM antes de comparar execuções;
- meça backlog/consumer lag e respostas, não apenas requests enviados;
- trate saturação ou partição bloqueada como resultado do cenário, não como capacidade externa.

## Limite arquitetural

O objetivo de milhares de requests por minuto deve ser certificado nos owners produtivos por relatórios datados e gates de carga. Este simulador pode gerar cenários determinísticos para esses testes, mas seus resultados isolados não autorizam sizing, SLA ou promoção.
