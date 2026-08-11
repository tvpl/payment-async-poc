# Segurança

`POST /jobs` e `GET /jobs/{id}` exigem `X-API-Key` quando `async.redis.security.enabled=true` (padrão). Produção recusa a chave de desenvolvimento (`dev-key-change-me`), uma chave em branco, autenticação desligada, idempotência opcional ou admissão desabilitada — `ProductionAcceptanceGuard` falha o startup, não a primeira requisição (SEC-01). `X-API-Key` real é proporcional a este exemplo; um serviço produtivo real deveria mover para JWT/OAuth2 + mTLS, como documentado no código (`AsyncSecurityProperties`).

Idempotência usa `SET NX` com fingerprint do payload: mesma chave e mesmo payload retorna o job original; mesma chave com payload diferente é `409 CONFLICT`, nunca um replay silencioso.

A imagem usa UID/GID `10001:10001`, base `eclipse-temurin:21-jre-alpine` fixada por tag e digest, sem pacote adicional no healthcheck (`wget` já vem da base). O Compose aplica root filesystem read-only, `/tmp` limitado, `cap_drop: ALL` e `no-new-privileges` (SEC-07). Não há segredo no build context ou na imagem.

CI gera SBOM SPDX e executa scan da imagem; severidade HIGH/CRITICAL bloqueia o gate (SEC-08).
