package com.example.activation.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validação de JWT com chave simétrica, SOMENTE para ambiente local/docker-compose,
 * onde não há servidor de autorização. Em produção usa-se issuer-uri (chaves via JWKS).
 */
@ConfigurationProperties("activation.security.local-jwt")
public record LocalJwtProperties(String hmacSecret, String issuer, String audience) {
}
