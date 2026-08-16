# Documentação — feature-control

- [Arquitetura](architecture.md)
- [Adoção](adoption.md)
- [Configuração](configuration.md)
- [Segurança](security.md)
- [Operação](operations.md)
- [Testes](testing.md)
- [ADRs](adr/README.md)

Escopo proporcional ao tipo de projeto: uma biblioteca Java publicada mais dois exemplos `NON_PRODUCTION` — sem contrato HTTP próprio para versionar, sem runbooks de on-call (os exemplos não servem tráfego real) e sem gate de capacidade dedicado (isso é responsabilidade da integração cross-boundary, fora deste boundary).
