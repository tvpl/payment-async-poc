# Runbook — saturação da admissão

## Sinal

`429` subindo em `POST /payment-simulations`, ou `api_pending` crescendo junto com `api_wait_latency`.

## Diagnóstico

1. **Um tenant ou a rota inteira?** Compare a taxa de `429` com `api_requests_total`. Se a rota
   segue estável e um único chamador é rejeitado, o orçamento por tenant fez o trabalho dele: não
   há incidente, há um chamador acima da cota.
2. **O Redis está respondendo?** Sem Redis não existe janela compartilhada e cada instância cai
   para `limit-for-period / instances`. Rejeição bem abaixo do orçamento nominal, com erros de
   conexão ao Redis no log, é o modo degradado funcionando como projetado.
3. **`PAYMENT_API_INSTANCES` bate com as réplicas?** Se o valor está menor que a frota, a admissão
   degradada é mais generosa do que deveria; se está maior, é mais restritiva. Confira antes de
   mexer no orçamento.
4. **O downstream desacelerou?** `api_timeouts_total` em alta com `api_completed_total` estável
   aponta para o Core, não para a API. Aumentar `wait-timeout` não cria capacidade, só alonga a
   espera.

## Ação

- Chamador acima da cota: negociar a cota, ajustar `tenant-limit-for-period` para aquele ambiente.
- Redis fora: tratar como incidente do Redis. **Não** aumente `limit-for-period` para compensar,
  isso remove exatamente a proteção que sobra durante a falha.
- Réplicas alteradas: atualizar `PAYMENT_API_INSTANCES` no mesmo deploy que muda a contagem.
- Core lento: seguir o runbook do SBUS; a API deve continuar rejeitando em vez de acumular.

## Não fazer

- Não desligue a admissão para "destravar" tráfego. O orçamento existe porque o Core tem limite real.
- Não derrube o Compose removendo volumes: isso destrói estado compartilhado do sandbox.
