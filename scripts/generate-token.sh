#!/usr/bin/env bash
# Gera um JWT HS256 para testar a API localmente (docker-compose), sem servidor de autorização.
# Uso: scripts/generate-token.sh [client_id] ["escopos separados por espaço"]
# Em produção o token vem do fluxo client_credentials do emissor OAuth 2.0.
set -euo pipefail

SECRET="${LOCAL_JWT_SECRET:-local-dev-secret-change-me-at-least-32-bytes}"
CLIENT_ID="${1:-erp-system}"
SCOPE="${2:-activation:read activation:write}"
NOW=$(date +%s)
EXP=$((NOW + 3600))

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"iss":"local-issuer","aud":"product-activation-service","sub":"%s","client_id":"%s","scope":"%s","iat":%d,"exp":%d}' \
  "$CLIENT_ID" "$CLIENT_ID" "$SCOPE" "$NOW" "$EXP" | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)

echo "$HEADER.$PAYLOAD.$SIGNATURE"
