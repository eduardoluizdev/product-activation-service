package com.example.activation.application;

/** Dados de entrada para criar uma solicitação, já com o cliente autenticado. */
public record NewActivationRequest(String clientId, String externalId, String customerId, String productCode) {
}
