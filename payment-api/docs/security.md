# Segurança

## Autenticação e autorização

- `POST/GET /payment-simulations` exige `X-API-Key`.
- `/admin/**` exige `ROLE_ADMIN` vindo do JWT.
- `/health/liveness` e `/health/readiness` são anônimos; qualquer outra rota cai no
  `isAuthenticated()` do catch-all.
- Endpoints de management estão desligados por padrão; só `health` (`details-visible: NEVER`) e
  `prometheus` (autenticado) existem.

## Perfil produtivo

`ProductionSecurityGuard` derruba o startup em `prod` quando: JWKS ou issuer não são HTTPS válidos,
`audience` está ausente, o clock skew não é zero, a autenticação por API key está desabilitada, ou a
lista de chaves está vazia, em branco ou usa o default de desenvolvimento. O emissor de token de
desenvolvimento é excluído do bean graph em `prod`, então a rota nem existe. O segredo HS256 vive
apenas em `application-dev.yml`: produção não declara segredo simétrico algum, o que elimina um
validador paralelo capaz de aceitar um JWT forjado.

## Segredos

Nenhum segredo é versionado. `.env.example` lista as variáveis sem valor atribuído;
`PAYMENT_API_KEY`, `JWT_JWKS_URL`, `JWT_ISSUER` e `JWT_AUDIENCE` vêm do secret manager do ambiente.
A credencial de um tenant nunca é usada em texto: a admissão a identifica por hash SHA-256
truncado, então nenhuma chave de Redis, log ou métrica carrega a credencial.

## Runtime do container

- Base mínima, tag **e** digest fixados nas duas etapas do build.
- Executa como `10001:10001`, usuário sem privilégio criado no próprio Dockerfile.
- Filesystem read-only, `/tmp` em tmpfs `noexec,nosuid,nodev`.
- `cap_drop: ALL` e `no-new-privileges:true`.
- Healthcheck usa o `wget` já presente na base: nenhum pacote extra é instalado no runtime.

## Cadeia de suprimentos

A CI local desta fronteira constrói a imagem, gera SBOM SPDX (`anchore/sbom-action`) e roda
`aquasecurity/trivy-action` com `severity: HIGH/CRITICAL` e `exit-code: '1'`: achado de severidade
alta ou crítica **bloqueia** o pipeline. O Dependabot acompanha Gradle, Docker e GitHub Actions.
