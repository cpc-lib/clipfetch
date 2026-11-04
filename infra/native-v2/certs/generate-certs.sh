#!/usr/bin/env bash
set -euo pipefail

CERT_DIR=/certs
mkdir -p "$CERT_DIR"

if [[ ! -f "$CERT_DIR/ca.crt" ]]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
    -keyout "$CERT_DIR/ca.key" -out "$CERT_DIR/ca.crt" \
    -subj "/CN=clipfetch-acceptance-ca" >/dev/null 2>&1

  openssl req -newkey rsa:2048 -nodes \
    -keyout "$CERT_DIR/server.key" -out "$CERT_DIR/server.csr" \
    -subj "/CN=worker" >/dev/null 2>&1
  openssl x509 -req -in "$CERT_DIR/server.csr" -days 365 \
    -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
    -extfile <(printf "subjectAltName=DNS:worker,DNS:localhost,IP:127.0.0.1") \
    -out "$CERT_DIR/server.crt" >/dev/null 2>&1

  openssl req -newkey rsa:2048 -nodes \
    -keyout "$CERT_DIR/client.key" -out "$CERT_DIR/client.csr" \
    -subj "/CN=clipfetch-api" >/dev/null 2>&1
  openssl x509 -req -in "$CERT_DIR/client.csr" -days 365 \
    -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" -CAcreateserial \
    -out "$CERT_DIR/client.crt" >/dev/null 2>&1

  rm -f "$CERT_DIR"/*.csr "$CERT_DIR"/*.srl
  chmod 644 "$CERT_DIR"/*.crt "$CERT_DIR"/*.key
  echo "acceptance certificates generated"
fi

exec sleep infinity
