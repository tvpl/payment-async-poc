# Segurança

## Classificação

O processo é `NON_PRODUCTION` e não recebe dados sensíveis ou credenciais de ambiente produtivo. Fixtures devem usar identificadores e payloads sintéticos.

## Contêiner

- bases mínimas suportadas e fixadas por tag + digest;
- runtime como UID/GID `10001:10001`;
- root filesystem read-only e `/tmp` limitado em tmpfs;
- todas as Linux capabilities removidas e `no-new-privileges` habilitado;
- healthcheck usa ferramenta já presente na base, sem instalar pacote adicional.

## Secrets e dependências

`.env.example` contém somente defaults locais. Credenciais de repositório Maven são fornecidas pelo ambiente/CI e não entram em imagem, logs ou build context. Kafka, Registry e collector devem permanecer isolados na rede local do sandbox.

## Limites

Não há autenticação ou autorização de API porque não há endpoint de negócio. `/health`, `/prometheus` e demais endpoints Micronaut habilitados destinam-se exclusivamente ao ambiente local; não os exponha publicamente.
