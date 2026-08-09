# Segurança

Esta biblioteca não autentica usuários e não armazena credenciais. Autenticação do Registry, TLS, autorização Kafka e tratamento de segredos pertencem ao serviço consumidor e ao ambiente.

Regras desta fronteira:

- nenhum token, senha, certificado ou `.env` entra em schema, fixture, log ou POM;
- produção desabilita auto-registration para impedir criação de contratos pela aplicação;
- registro de schema é uma etapa separada, autorizada e auditável;
- artefatos publicados não incorporam endpoints ou credenciais de ambiente;
- falhas de codec não incluem payload nem configuração sensível na mensagem.

O manifest contém identificadores públicos do contrato, não segredos.
