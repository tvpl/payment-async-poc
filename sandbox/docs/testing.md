# Testes e gates

```bash
make config
make verify-structural
make verify-runtime
```

`verify-structural` valida:

- serviços permitidos por profile;
- rede, volumes e ausência de build/container name;
- ownership de observabilidade;
- quatro combinações de portas e variáveis;
- 12 pins, retenções e confirmação de reset;
- links, comandos, claims e ADRs.

`verify-runtime` executa nove probes de Kafka, Redis, PostgreSQL e Registry e cinco queries dos profiles. Um serviço não iniciado ou Docker indisponível é falha/`NOT_RUN`; nunca registre PASS sem saída real.

O teste de failure/recovery pode parar uma dependência sem remover dados, executar `smoke/verify.sh --skip-init --only <dependency>`, reiniciar com `docker compose start --wait` e repetir o probe.
