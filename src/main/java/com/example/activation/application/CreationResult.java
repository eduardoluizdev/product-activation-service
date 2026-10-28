package com.example.activation.application;

import com.example.activation.domain.ActivationRequest;

/**
 * @param created {@code false} quando a chamada foi um retry de uma solicitação já existente
 */
public record CreationResult(ActivationRequest request, boolean created) {
}
