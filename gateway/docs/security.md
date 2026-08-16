# Segurança — gateway

`NON_PRODUCTION`. Esta camada demonstra o desenho de segurança de borda; os
valores (senhas `-change-me`, HTTP interno, certificados locais) são de sandbox.

## Camadas de identidade

| Camada | Mecanismo | Quem valida |
| --- | --- | --- |
| Canal (máquina/parceiro) | mTLS no listener 10443 | Envoy (CA local do volume) |
| Usuário/cliente OAuth | Bearer JWT do realm `payments` | Envoy (JWKS do Keycloak; issuer, audience `payment-gateway`, expiração, assinatura) |
| Aplicação | `X-API-Key` | Edge (`ApiKeyFilter`), inalterado |

O JWT é removido pelo Envoy após a validação — o Edge nunca vê o token do
Keycloak. Isso evita colisão com o `Authorization` próprio do Edge (dev token
HS256 / JWKS de produção) e mantém explícito que autenticação de canal e de
aplicação são coisas diferentes.

## Superfície exposta (allowlist)

Expostos: `POST|GET /payment-simulations*`, `/v0/payment-simulations*`, `/health/*`.
Não expostos (404 do gateway): `/admin/**`, `/auth/**`, `/prometheus`, e
qualquer rota futura do Edge até ser adicionada explicitamente. Um endpoint
novo no Edge nasce **invisível** na borda — default deny.

## mTLS

O listener 10443 exige certificado de cliente assinado pela CA local
(`require_client_certificate: true`, TLS >= 1.2). Os certificados são gerados
pelo serviço `certs-init` no primeiro boot; `make certs-export` extrai o par de
cliente para teste com curl. Em produção: PKI real (cert-manager/Vault/HSM),
rotação automática, e revogação — nada disso existe aqui, de propósito.

## Rate limit como controle de abuso

Por origem (600/min) e por rota (100/s e 25/s). Vale notar que a rota
`/v0/payment-simulations` é anônima no Edge (feature flag decide), então o
gateway é hoje a **única** barreira de token na frente dela — motivo do limite
mais restrito. Fail-open deliberado: com o Rate Limit Service fora, a admissão
por rota/tenant do Edge continua de pé.

## Extension point: autorização fina (OPA / ext_authz)

Não incluído para manter o guardrail mínimo. Quando houver política real
(role→rota, claims→recurso, allowlist de merchants), o caminho é o filtro
`ext_authz` do Envoy entre o JWT e o rate limit, apontando para um OPA com
bundle versionado. O claim payload já fica disponível em dynamic metadata
(`jwt_payload`) para esse filtro consumir. Fazer RBAC com regex de rota no
próprio Envoy não escala em auditabilidade — prefira OPA quando chegar a hora.

## Limites conhecidos deste desenho

- Keycloak servido em HTTP puro no sandbox; o `ProductionSecurityGuard` do Edge
  exigiria HTTPS de issuer/JWKS num deploy real (e está certo em exigir).
- O tráfego gateway→Edge é HTTP claro na rede Docker; TLS/mTLS interno (service
  mesh ou upstream TLS do Envoy) segue pendência registrada em
  [production-evidence](../../docs/production-evidence.md).
- Os contadores do Rate Limit Service ficam num Redis sem senha, isolado na
  rede interna `payment-gateway` (não exposto no host e fora da rede do
  sandbox) — aceitável só porque não guarda dado de negócio.
