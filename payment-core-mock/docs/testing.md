# Testes

## Gate rápido

```bash
./gradlew test --no-daemon
```

Cobre decisão determinística, todos os outcomes, limites inclusivos, autorização, configuração inválida e isolamento estrutural. A contagem não deve diminuir silenciosamente.

## Contrato e integração

```bash
./gradlew test -PwithIT --no-daemon
```

Usa Kafka e Apicurio reais via Testcontainers. Prova round trip Avro, tópicos, key, headers, trace-id, correlação/causação, duplicata equivalente e retenção de offset para poison, failure profile e Registry outage. Docker indisponível é `NOT_RUN`, nunca `PASS`.

## Documentação e imagem

```bash
scripts/verify-docs.sh
python3 -m unittest discover -s deploy -p 'test_*.py'
deploy/verify.sh
```

O gate documental rejeita ausência de owner docs, link quebrado, ADR incompleto, claim incompatível e falta do label em README/startup/imagem/CI. O gate de deploy constrói e inicia o contêiner na rede sandbox, valida health, usuário, filesystem e label, e remove somente o projeto efêmero.

## Gate de encerramento

```bash
./gradlew build --no-daemon
git diff --check
```

Mudanças de evento também exigem os gates de compatibilidade no owner `payment-contracts`.
