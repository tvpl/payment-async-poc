# Segregação de Repositórios e Hardening de Produção — Contexto

**Gathered:** 2026-08-08
**Spec:** `.specs/features/repository-segregation-production-hardening/spec.md`
**Status:** Approved

---

## Feature Boundary

Esta feature reorganiza o workspace em fronteiras locais autossuficientes e prontas para futura extração em repositórios. Ela também corrige ou bloqueia os gaps que impedem uso produtivo, cria o sandbox exclusivo de infraestrutura compartilhada e substitui documentação centralizada ou incorreta por documentação, ADRs e instruções de IA pertencentes a cada fronteira.

A feature não cria repositórios remotos, não executa deploy e não escolhe uma plataforma de cloud. Contratos externos permanecem compatíveis durante a migração.

---

## Implementation Decisions

### Fronteiras e granularidade

- O workspace terá sete raízes: `payment-contracts`, `payment-api`, `payment-sbus`, `payment-core-mock`, `feature-control`, `async-redis-service` e `sandbox`.
- `feature-demo` e `pilot-app` serão exemplos internos do repositório `feature-control`, não produtos com release independente.
- `payment-core-mock` continuará separado do sandbox porque é um simulador de domínio, não infraestrutura compartilhada.
- O diretório genérico `common` deixará de existir. Contratos irão para `payment-contracts`; rate limiting e outras preocupações de runtime ficarão nos serviços proprietários ou em biblioteca explicitamente decidida no design.
- O trabalho permanecerá em um único repositório Git até todos os gates locais passarem. A extração remota será uma iniciativa posterior.

### Dependências e releases

- `payment-api`, `payment-sbus` e `payment-core-mock` consumirão `payment-contracts` como artefato Maven versionado.
- Aplicações consumidoras usarão `feature-control` como artefato Maven versionado.
- Um repositório Maven local e, se útil, composite build explícito poderão acelerar desenvolvimento, mas a declaração de dependência produtiva continuará versionada.
- Bibliotecas terão pipeline de publicação e compatibilidade. Não terão Dockerfile ou Compose de runtime sem processo executável.

### Compose e sandbox local

- Cada aplicação deployável terá Dockerfile e Compose próprios.
- O Compose de uma aplicação conterá somente a aplicação, seus mocks exclusivos quando necessários e conexão à rede externa criada pelo sandbox.
- `/sandbox` conterá Kafka, Redis, PostgreSQL, Schema Registry, observabilidade e ferramentas de inspeção.
- O sandbox terá profiles separados para infraestrutura mínima, observabilidade e ferramentas de inspeção.
- Dashboards, alertas e métricas específicos continuarão pertencendo à aplicação; o sandbox apenas realizará a montagem local desses artefatos por mecanismo versionável.

### Produção versus demonstração

- `payment-api`, `payment-sbus`, `payment-contracts`, `feature-control` e `async-redis-service` terão critérios explícitos de produção.
- `payment-core-mock`, `feature-demo` e `pilot-app` serão identificados como `NON_PRODUCTION` no README, configuração, imagem e CI aplicáveis.
- Emissores JWT, failure hooks e identidades simuladas existirão somente em profiles de desenvolvimento/teste e estarão ausentes do bean graph produtivo.
- Documentação não poderá usar “production ready” sem evidência dos gates correspondentes.

### Capacidade e saturação

- O alvo inicial será 10.000 requisições por minuto durante 15 minutos e rajada de 20.000 por minuto durante 60 segundos.
- A capacidade será avaliada em ambiente de referência versionado, com duas instâncias de API e duas de SBUS.
- Requisições aceitas não poderão sofrer perda silenciosa. Excesso de capacidade será rejeitado ou convertido para processamento assíncrono conforme contrato.
- A capacidade inferior do Core será tratada como restrição real. Kafka e Redis poderão amortecer rajadas limitadas, mas não justificarão backlog ilimitado ou SLO impossível.

### Compatibilidade e migração

- Endpoints, tópicos, schemas Avro e migrations existentes permanecerão compatíveis durante os moves.
- Migrations Flyway continuarão append-only.
- Cada fase produzirá equivalência antes da remoção da localização antiga.
- Documentação antiga só será removida depois de conteúdo válido ter destino e de links/claims serem verificados.
- A sequência favorecerá commits atômicos, rollback e gates por fronteira.

### Documentação, ADRs e IA

- A documentação operacional será escrita em pt-BR; código, nomes técnicos e commits permanecerão em inglês.
- Cada raiz terá `AGENTS.md` com fontes de verdade, mapa, invariantes, ownership, proibições e comandos locais.
- Cada raiz terá `docs/adr/README.md` e ADRs numerados apenas para decisões difíceis de reverter, surpreendentes e baseadas em trade-off.
- Decisões transversais também serão registradas de forma concisa em `.specs/STATE.md`.
- A documentação raiz final será um mapa do workspace e do sandbox, sem repetir detalhes dos projetos.

### Agent's Discretion

- Nomes exatos de packages internos, plugins Gradle auxiliares e scripts, desde que não criem nova dependência cross-root.
- Ferramenta concreta de lint de Markdown, link checking, análise estática Java, coverage e compatibilidade binária, após pesquisa no design e com gates determinísticos.
- Forma exata de montar dashboards específicos no sandbox local, preservando ownership e portabilidade.
- Divisão em subfases e ordem fina de moves, desde que respeite equivalência, dependências e rollback.

### Declined / Undiscussed Gray Areas → Assumptions

Nenhuma. O usuário aprovou todos os defaults registrados na especificação em 2026-08-08.

---

## Specific References

- O comportamento executável e os testes existentes permanecem acima da documentação antiga na hierarquia de fontes de verdade.
- A meta é obter um workspace que possa ser separado em repositórios sem novo redesenho estrutural.
- A qualidade esperada é de referência arquitetural, com revisão de produção e capacidade comprovável, não apenas organização visual de diretórios.

---

## Deferred Ideas

- Criação dos repositórios remotos e extração de histórico Git.
- Escolha de cloud, Kubernetes, service mesh e serviços gerenciados.
- Deploy ou publicação externa de imagens e bibliotecas.
- Integração com o Core de pagamento real e certificação da capacidade desse sistema externo.
