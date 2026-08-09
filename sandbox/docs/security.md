# Segurança

O sandbox é restrito ao desenvolvimento local. Kafka e Redis usam transporte sem autenticação; PostgreSQL e Grafana exigem secrets locais fornecidos por `.env`. Não exponha suas portas fora de uma estação controlada.

- Nunca versione `.env`, dumps, tokens ou senhas.
- Digests fixos tornam a imagem reprodutível, mas não substituem atualização e scan de vulnerabilidade.
- Kafka UI e UIs de observabilidade são ferramentas locais, não painéis públicos.
- O reset destrutivo exige token explícito e continua sujeito a autorização humana.
- Ambientes produtivos devem usar serviços gerenciados/HA, TLS, autenticação, backups e políticas do owner operacional.
