# ADR 0009 — Segurança: OAuth 2.0 client credentials e Resource Server JWT

**Status:** Aceita

## Contexto

A API é consumida por outros sistemas, não por usuários. O enunciado pede para implementar ou
descrever a proteção com Spring Security, JWT e OAuth 2.0, sem criar servidor de autenticação.

## Decisão

**Implementado:**

1. **Fluxo OAuth 2.0 client credentials.** É o fluxo para comunicação máquina a máquina: cada sistema
   consumidor tem `client_id`/`client_secret` (ou, melhor, `private_key_jwt`/mTLS) no servidor de
   autorização corporativo (Keycloak, Entra ID, Cognito…) e obtém um token de curta duração. Não há
   usuário nem redirecionamento, então authorization code não se aplica.
2. **Spring Security como Resource Server JWT**, stateless, sem sessão nem CSRF.
3. **Validação do token**: assinatura (chaves públicas do JWKS do emissor, descobertas via
   `issuer-uri` e com cache/rotação feitos pelo Spring), `iss`, `aud = product-activation-service`,
   `exp` e `nbf`.
4. **Autorização por escopo**:
   - `activation:write` → `POST /api/v1/requests`
   - `activation:read` → `GET /api/v1/requests` e `GET /api/v1/requests/{id}`
   - qualquer outra rota de negócio → negada; health e métricas → liberadas (ver Consequências).
5. **Isolamento entre consumidores (OWASP API1 — BOLA)**: a solicitação guarda o `client_id` do token
   que a criou. Consulta por id e listagem sempre filtram por ele; o id de outro cliente retorna
   **404** (não 403), para não confirmar que o recurso existe.
6. **Ambiente local sem servidor de autorização**: com a propriedade
   `activation.security.local-jwt.hmac-secret` definida (só no docker-compose), o serviço valida
   tokens HS256 gerados por `scripts/generate-token.sh`, com as mesmas checagens de `iss`, `aud` e
   `exp`. Em produção a propriedade não existe e vale o `issuer-uri`; sem nenhum dos dois a aplicação
   não sobe (falha rápida em vez de ficar aberta).

**Descrito (não implementado):**

- **Credencial para chamar o provedor**: ficaria em um gerenciador de segredos (AWS Secrets Manager,
  Azure Key Vault, Vault), injetada como variável de ambiente ou lida via Spring Cloud. Rotação com
  duas credenciais válidas ao mesmo tempo no provedor: publica a nova, a aplicação passa a usá-la
  (restart ou refresh), revoga a antiga. Se o provedor aceitar OAuth, o serviço seria ele mesmo um
  cliente client credentials (`spring-boot-starter-oauth2-client` + interceptor no `RestClient`).
- **Limite de taxa e de tamanho do corpo**: no API Gateway/Ingress (por `client_id`), que é onde
  esses limites são aplicados de forma consistente entre instâncias. Dentro do serviço os campos já
  têm tamanho máximo de 64 caracteres.

## Alternativas consideradas

- **API key estática**: simples, mas sem expiração, sem escopo e difícil de rotacionar.
- **Escopo `write` implicando `read`**: preferi escopos independentes; um sistema que só envia
  pedidos não precisa ler os dados de volta.

## Consequências

- Testes de 401/403 e de isolamento usam `SecurityMockMvcRequestPostProcessors.jwt()`, sem servidor
  de autorização real.
- `/actuator/prometheus` está liberado para simplificar o compose; em produção ficaria em
  `management.server.port` separado, acessível só pela rede interna do cluster.
