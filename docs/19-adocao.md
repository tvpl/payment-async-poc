# 19 — Adoção do feature-control nas 30+ apps

Guia passo a passo para uma aplicação passar a usar a lib `feature-control`. O molde executável é o
serviço **`pilot-app`** (:8085) — copie os pontos dele. Veja também [16](16-feature-control-lib.md)
(arquitetura) e [18](18-operacao-features.md) (operação).

## 1. Adicionar a dependência (por versão)

```groovy
// build.gradle da app consumidora
implementation 'com.example.platform:feature-control:0.1.0'
implementation 'io.micronaut.security:micronaut-security-jwt'   // reconhecer usuário/grupos
implementation 'io.micronaut.redis:micronaut-redis-lettuce'      // store dinâmico (opcional)
annotationProcessor 'io.micronaut.security:micronaut-security-annotations'
```

O artefato é publicado no GitHub Packages (ver [16](16-feature-control-lib.md#distribuição-da-lib-maven)).
No monorepo deste PoC o `pilot-app` usa `implementation project(':feature-control')` — numa app
separada, troque pela coordenada versionada acima.

## 2. Definir o baseline em YAML

```yaml
platform:
  features:
    redis-enabled: true       # false = só YAML (sem Redis)
    cache-ttl: 5s
    key-prefix: "feature:"
    flags:
      minha-flag:
        type: PERCENTAGE      # BOOLEAN | PERCENTAGE | ALLOWLIST | VARIANT
        enabled: true
        percentage: 10
        on-variant: v2
        off-variant: v1
```

## 3. Configurar segurança (JWT) e o admin

```yaml
micronaut:
  security:
    authentication: bearer
    intercept-url-map:
      - pattern: /admin/**        # se expuser o admin de flags
        access: [ROLE_ADMIN]
      - pattern: /**
        access: [isAnonymous()]
    token:
      jwt:
        signatures:
          secret:                 # dev (HS256). Prod: RS256/JWKS (application-prod.yml)
            generator:
              secret: ${JWT_SIGNATURE_SECRET}
              jws-algorithm: HS256
```

## 4. Usar no código

**Imperativo** (ramificar por decisão):
```java
FeatureContext ctx = JwtFeatureContextFactory.from(authentication, anonId);
if (features.evaluate("minha-flag", ctx).isOn()) { ... } else { ... }
```

**Declarativo** (esconder a rota quando off):
```java
@Get("/v2/report")
@FeatureGate("relatorio-v2")   // 404 para quem não é elegível
Report v2() { ... }
```

Injete `FeatureResolver` (e opcionalmente `ApiVersionResolver`, `TopicRouter`). Tudo já vem como bean
da lib.

## 5. Controle em runtime (opcional, requer Redis)

`PUT /admin/features/{name}` (ROLE_ADMIN) sobrepõe o baseline sem redeploy, com propagação instantânea
e concorrência otimista (409). Ver o runbook [18](18-operacao-features.md).

## 6. Testar

```java
@MicronautTest
class MinhaFeatureIT {
  @Inject @Client("/") HttpClient client;
  @Inject TokenGenerator tokens;     // do micronaut-security-jwt, para gerar JWT de teste
  // ... GET com bearerAuth(token com o grupo) -> comportamento esperado
}
```
Modelo pronto: `pilot-app/src/test/java/.../PilotIT.java` (resolver + `@FeatureGate`).

## 7. HA e observabilidade (grátis)

- **Redis HA**: defina `REDIS_URI=redis-sentinel://.../mymaster` — sem mudança de código ([15](15-prontidao-producao.md#redis-ha-sentinelcluster)).
- **Métricas**: se a app tiver micrometer, `feature_decisions_total` já é exportado (dashboard **Feature Decisions**).

## Checklist de adoção
- [ ] Dependência `feature-control` (+ security-jwt, redis-lettuce se dinâmico).
- [ ] Baseline `platform.features.flags.*` em YAML.
- [ ] Segurança JWT (dev HS256 / prod JWKS) + `/admin/**` = ROLE_ADMIN.
- [ ] Chamadas via `FeatureResolver`/`@FeatureGate` com `FeatureContext` do JWT.
- [ ] Teste `@MicronautTest` cobrindo on/off por grupo.
- [ ] `REDIS_URI` (Sentinel) no ambiente de produção.

## Ver também
- [16 Feature Control (lib)](16-feature-control-lib.md) · [18 Operação](18-operacao-features.md) · [15 Prontidão](15-prontidao-producao.md)
