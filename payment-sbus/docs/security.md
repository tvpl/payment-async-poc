# Segurança

Produção aceita JWT RSA por JWKS HTTPS e valida issuer, audience, expiração, not-before e clock skew estrito. `/internal/**` exige identidade `ROLE_PAYMENT_API`. Somente `/health/liveness` e `/health/readiness` são anônimos; métricas e detalhes permanecem protegidos.

A imagem usa UID/GID `10001:10001`. O Compose aplica root filesystem read-only, `/tmp` limitado, `cap_drop: ALL` e `no-new-privileges`. Bases são fixadas por tag e digest. Não há segredo no build context ou na imagem.

CI gera SBOM SPDX e executa scan de filesystem e imagem. Severidade HIGH/CRITICAL bloqueia o gate; exceções exigem decisão documentada e prazo.
