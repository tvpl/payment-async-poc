# ADR 0001 — Envoy estático como guardrail NON_PRODUCTION opcional

## Status

Aceita (2026-08).

## Contexto

O workspace demonstra um padrão sync-to-async bancário em que o Edge
(`payment-api`) acumula duas famílias de responsabilidade: as de aplicação
(idempotência, espera coordenada, degradação para 202) e as que num deploy real
pertencem à borda (autenticação de canal, corte de abuso, proteção de conexão).
Faltava uma camada que tornasse essa separação visível e testável ponta a ponta
— sem tocar no código das aplicações e sem virar dependência dos testes rápidos.

## Decisão

1. Nova fronteira `gateway/` com **Envoy em configuração estática** (um
   `envoy.yaml` comentado), Keycloak como IdP local com realm importado no boot,
   e o Rate Limit Service de referência do Envoy com Redis privado.
2. A camada é **opcional por contrato**: nenhuma outra fronteira a referencia;
   todos os smokes/gates existentes continuam indo direto ao Edge.
3. Marcada `NON_PRODUCTION`, como o `payment-core-mock`: demonstra o desenho,
   não os valores (senhas de teste, HTTP interno, certificados locais).

## Alternativas consideradas

- **Envoy Gateway (implementação da Gateway API)**: é o alvo natural em
  Kubernetes, mas exige um cluster e um control plane inteiros para expressar o
  que aqui são ~300 linhas auditáveis de configuração estática. O desenho das
  rotas/filtros migra 1:1 para HTTPRoute/SecurityPolicy/BackendTrafficPolicy
  quando houver K8s; a decisão de *semântica* (o que a borda faz) fica
  registrada aqui e sobrevive à troca de mecanismo. **Atualização (K8S-01..05,
  2026-08)**: esse caminho agora existe como um segundo path, não mais só uma
  alternativa hipotética - ver [`k8s/`](../../k8s/README.md). O compose
  continua sendo o guardrail padrão para desenvolvimento local; os manifests
  K8s existem para quem já tem um cluster e quer a paridade semântica
  verificada em gate, sem exigir Kubernetes como pré-requisito de ninguém que
  só quer rodar o workspace localmente.
- **Kong/nginx**: perderiam a simetria com o ecossistema Envoy (mesmo modelo de
  circuit breaking, rate limit service e telemetria que um mesh usaria).
- **Embutir OIDC no Edge**: contamina a aplicação com preocupação de canal e
  impede testar o Edge isolado — exatamente o que o workspace quer evitar.

## Consequências

- E2E com autenticação real de borda vira um `make up` a mais, sem mudança de
  código nas aplicações.
- Dois valores ficam acoplados por design (issuer fixado ao par
  `KC_HOSTNAME`/porta 8086; upstream `api:8080`) — documentados em
  `configuration.md`, aceitáveis num arquivo estático de sandbox.
- A autorização fina (OPA/ext_authz) fica como extension point documentado, não
  implementado: o custo de manter política de exemplo superaria o valor de
  demonstração.
- O gateway não entra no alvo de capacidade certificado; carga continua sendo
  medida direto no Edge.
