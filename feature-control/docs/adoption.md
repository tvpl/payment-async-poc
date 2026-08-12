# Adoção

Guia passo a passo para uma aplicação começar a usar a lib `feature-control`. O molde executável é o
exemplo `pilot-app` (`examples/pilot-app`): copie os pontos dele. `feature-demo` (`examples/feature-demo`,
porta 8083) cobre os quatro cenários de flag com um endpoint por cenário. Ver [arquitetura](architecture.md)
para o desenho interno e [operação](operations.md) para o dia a dia de quem já adotou.

## 1. Adicionar a dependência

```groovy
// build.gradle da app consumidora
implementation 'com.example.platform:feature-control:0.1.0'
implementation 'io.micronaut.security:micronaut-security-jwt'   // reconhecer usuário/grupos
implementation 'io.micronaut.redis:micronaut-redis-lettuce'      // store dinâmico (opcional)
annotationProcessor 'io.micronaut.security:micronaut-security-annotations'
```

O artefato é publicado como `com.example.platform:feature-control` (POM, jar, sources, javadoc) — ver
[publicação e consumer fixture](configuration.md#publicação-e-consumer-fixture). `feature-demo` e
`pilot-app` vivem no mesmo build Gradle da biblioteca e por isso dependem do projeto local
`:feature-control` em vez da coordenada versionada; uma app fora deste boundary consome sempre o GAV
publicado acima.

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

Este é o baseline mínimo. Propriedades adicionais (`cache-ttl-jitter`, `max-stale`, `stale-fallback`,
`master-enabled`, `metric-cardinality-limit`, entre outras) têm efeito e padrão documentados em
[configuração](configuration.md).

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

`JWT_SIGNATURE_SECRET` não tem default: sem ele, o boot falha. Em produção, valide contra o JWKS do
seu IdP (RS256), não contra um segredo compartilhado — ver [segurança](security.md).

## 4. Usar no código

**Imperativo** (ramificar por decisão):

```java
FeatureContext ctx = JwtFeatureContextFactory.from(authentication, anonId);
if (features.isEnabled("minha-flag", ctx)) { ... } else { ... }
```

**Declarativo** (esconder a rota quando off):

```java
@Get("/v2/report")
@FeatureGate("relatorio-v2")   // 404 para quem não é elegível
Report v2() { ... }
```

Injete `FeatureResolver` (e, se aplicável, `ApiVersionResolver` para versionamento de API ou
`TopicRouter` para roteamento A/B de tópico Kafka) — todos já vêm como bean da lib.

## 5. Controle em runtime (opcional, requer Redis)

`PUT /admin/features/{name}` (exige `ROLE_ADMIN`) sobrepõe o baseline sem redeploy, com propagação
em milissegundos e concorrência otimista (`version`, HTTP 409 em conflito). Ver o dia a dia em
[operação](operations.md).

## 6. Testar

```java
@MicronautTest
class MinhaFeatureIT {
  @Inject @Client("/") HttpClient client;
  @Inject TokenGenerator tokens;     // do micronaut-security-jwt, para gerar JWT de teste
  // ... GET com bearerAuth(token com o grupo) -> comportamento esperado
}
```

Modelo pronto: `examples/pilot-app/src/test/java/com/example/platform/pilot/PilotIT.java` (resolver +
`@FeatureGate`).

## 7. HA e observabilidade

- **Redis**: aponte `redis.uri` para a instância do seu ambiente
  (`redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}` é o padrão dos exemplos). A lib não assume uma
  topologia específica, só que a URI configurada resolve para um Redis alcançável.
  <!-- TODO verify: nenhum teste deste boundary exercita Sentinel/Cluster; confirmar antes de prometer
       suporte HA explícito a quem adota. -->
- **Métricas**: se a app tiver `micrometer` no classpath, `feature_decisions_total` já é exportado
  automaticamente (ver [arquitetura](architecture.md)) — nada a configurar além disso.

## Checklist de adoção

- [ ] Dependência `feature-control` (+ `security-jwt`, `redis-lettuce` se dinâmico).
- [ ] Baseline `platform.features.flags.*` em YAML.
- [ ] Segurança JWT (dev HS256 / prod JWKS) + `/admin/**` = `ROLE_ADMIN`.
- [ ] Chamadas via `FeatureResolver`/`@FeatureGate` com `FeatureContext` derivado do JWT.
- [ ] Teste `@MicronautTest` cobrindo on/off por grupo.
- [ ] `redis.uri` apontando para a instância correta do ambiente de produção.
