package com.example.activation.infrastructure.provider;

/** Corpo enviado ao provedor. Contrato próprio, desacoplado da entidade e da API pública. */
record ProviderActivationPayload(String requestId, String externalId, String customerId, String productCode) {
}
