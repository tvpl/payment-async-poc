# ADR-0001 — Exemplos recusam inicialização em produção

Status: Accepted
Date: 2026-08-10

## Contexto

`feature-demo`/`pilot-app` existem para demonstrar a adoção da biblioteca (JWT de desenvolvimento self-issued, endpoints de toggle/gate sem autenticação real, endpoint admin sem identidade de operador real por trás). AD-005 já os classifica `NON_PRODUCTION`, mas nada no código impedia que um deploy acidental os levantasse com `env=prod`: `DevTokenController` documentava "Not for production" só em Javadoc, e nenhuma rota tinha `@Requires(notEnv = "prod")`. SEC-01 exige recusa de inicialização em produção quando um segredo/credencial obrigatório está ausente ou usa default de dev; SEC-02 exige que emissores de token e endpoints de demonstração saiam do bean graph e da superfície HTTP sob `prod`.

## Decisão

Cada exemplo carrega um `NonProductionExampleGuard` (`@Context @Requires(env = "prod")`) cujo construtor lança `ConfigurationException` incondicionalmente. Diferente de um `ProductionAcceptanceGuard` real (que valida configuração e segue adiante quando ela é válida — padrão usado em `payment-api`/`async-redis-service`), este guard não tem "caminho válido": não existe uma configuração de produção legítima para um app cujo propósito inteiro é demonstração. A inicialização falha antes que qualquer controller (token, admin, demo) entre no bean graph — a exclusão de rota individual que SEC-02 pede é, aqui, um efeito colateral estrito de o contexto inteiro nunca terminar de subir.

## Alternativas consideradas

1. **`@Requires(notEnv = "prod")` em cada controller individualmente** (o padrão usado por `payment-api` no seu emissor de token dev): rejeitada como única defesa — funciona, mas deixa o resto do app (métricas, health, outros beans) rodando sob `prod` sem nenhum propósito, e exige lembrar de anotar cada novo controller que alguém adicionar no futuro. Documentado aqui como reforço válido, não escolhido por não ser necessário: o guard de startup já torna a questão irrelevante.
2. **Checagem em runtime dentro de cada handler** (retornar 404/403 se `env==prod`): rejeitada — reativa por rota, mesmo problema de "lembrar de adicionar" que a alternativa 1, e mais tardia (a app já está de pé, servindo outras rotas).
3. **Confiar em documentação/README + processo de deploy**: rejeitada — é exatamente a lacuna que gerou este ADR; "Not for production" em Javadoc não impediu nada estruturalmente.

## Consequências

- `feature-demo`/`pilot-app` são estruturalmente inelegíveis para deploy de produção; um pipeline que tentar subi-los com `env=prod` falha no boot, não silenciosamente em runtime.
- Um app consumidor real que queira reutilizar código destes exemplos como ponto de partida deve remover o guard deliberadamente — o que é o comportamento certo: sinaliza a intenção explícita de sair do status `NON_PRODUCTION`.
- `NonProductionGuardIT` (3 testes por exemplo) cobre: recusa sob `prod` sozinho, recusa sob `prod` combinado com outro environment, e sucesso normal sob um environment não-`prod` — o guard nunca bloqueia uso legítimo de demonstração/teste.

## Supersession

Nenhum ADR substitui esta decisão. Promover qualquer um dos dois exemplos a um serviço com status de produção exige novo ADR e os gates de produção completos (SEC-01 a SEC-05, capacidade, observabilidade) que este boundary hoje não tem.
