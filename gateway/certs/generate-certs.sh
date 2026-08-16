#!/bin/sh
# Gera CA + certificado de servidor + certificado de cliente para o listener mTLS
# do Envoy (porta 10443). Roda dentro do serviço certs-init do compose, gravando
# no volume gateway-certs. Idempotente: não regenera se os artefatos já existem.
#
# NON_PRODUCTION: chaves ficam legíveis no volume para o processo do Envoy (uid 101)
# e para exportação de teste. Em produção, certificados vêm de uma PKI real
# (cert-manager, Vault, ACM) e nunca de um volume compartilhado.
set -eu

CERT_DIR="${CERT_DIR:-/certs}"
DAYS="${CERT_DAYS:-365}"

if [ -f "$CERT_DIR/server.crt" ] && [ -f "$CERT_DIR/client.crt" ] && [ -f "$CERT_DIR/ca.crt" ]; then
  echo "certs-init: certificados já existem em $CERT_DIR, nada a fazer"
  exit 0
fi

echo "certs-init: gerando CA, servidor e cliente em $CERT_DIR"

# CA local
openssl genrsa -out "$CERT_DIR/ca.key" 2048
openssl req -x509 -new -nodes -key "$CERT_DIR/ca.key" -sha256 -days "$DAYS" \
  -subj "/CN=payment-gateway-local-ca" -out "$CERT_DIR/ca.crt"

# Servidor (SANs cobrem acesso pelo host e pela rede interna)
openssl genrsa -out "$CERT_DIR/server.key" 2048
openssl req -new -key "$CERT_DIR/server.key" \
  -subj "/CN=payment-gateway" -out "$CERT_DIR/server.csr"
cat > "$CERT_DIR/server.ext" <<EOF
subjectAltName = DNS:localhost, DNS:envoy, DNS:payment-gateway, IP:127.0.0.1
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
EOF
openssl x509 -req -in "$CERT_DIR/server.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial -days "$DAYS" -sha256 -extfile "$CERT_DIR/server.ext" -out "$CERT_DIR/server.crt"

# Cliente (para curl --cert/--key nos testes de mTLS)
openssl genrsa -out "$CERT_DIR/client.key" 2048
openssl req -new -key "$CERT_DIR/client.key" \
  -subj "/CN=payment-test-client" -out "$CERT_DIR/client.csr"
cat > "$CERT_DIR/client.ext" <<EOF
keyUsage = digitalSignature
extendedKeyUsage = clientAuth
EOF
openssl x509 -req -in "$CERT_DIR/client.csr" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
  -CAcreateserial -days "$DAYS" -sha256 -extfile "$CERT_DIR/client.ext" -out "$CERT_DIR/client.crt"

rm -f "$CERT_DIR"/*.csr "$CERT_DIR"/*.ext "$CERT_DIR"/*.srl

# O Envoy roda como uid 101 e o volume nasce root; leitura de grupo/outros é
# aceitável apenas neste sandbox local (ver aviso NON_PRODUCTION acima).
chmod 644 "$CERT_DIR"/*.crt "$CERT_DIR"/*.key

echo "certs-init: concluído"
ls -l "$CERT_DIR"
