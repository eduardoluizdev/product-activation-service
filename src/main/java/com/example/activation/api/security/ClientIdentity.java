package com.example.activation.api.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identifica o sistema consumidor a partir do token (client credentials).
 * Usado para isolar os dados entre consumidores (OWASP API1 - BOLA).
 */
public final class ClientIdentity {

    private ClientIdentity() {
    }

    public static String of(Jwt jwt) {
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId == null) {
            clientId = jwt.getClaimAsString("azp");
        }
        if (clientId == null) {
            clientId = jwt.getSubject();
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Token sem identificação do cliente");
        }
        return clientId;
    }
}
