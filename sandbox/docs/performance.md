# Performance e capacidade local

O sandbox de nó único prova integração e comportamento de falha; não certifica capacidade produtiva. Kafka usa um broker/controller combinado, PostgreSQL uma instância, Redis um nó e Registry em memória.

Para testes de carga do workspace:

- monitore CPU, memória, disco, Kafka lag, conexões PostgreSQL e Redis;
- mantenha as retenções de `config/lifecycle.json` dentro do espaço disponível;
- execute carga somente após `make verify` verde;
- registre versão das imagens, profiles, recursos do Docker e data do relatório;
- não transforme resultados locais em claim de produção.

A meta cross-boundary de 10.000 req/min e spike de 20.000 req/min pertence ao gate final das aplicações, não a esta infraestrutura isolada.
