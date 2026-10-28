package com.example.activation.api;

import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.ActivationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * @param failureReason preenchido em FAILED, ou com o último erro enquanto aguarda nova tentativa
 */
public record ActivationResponse(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        ActivationStatus status,
        int attempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static ActivationResponse from(ActivationRequest request) {
        return new ActivationResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
                request.getProductCode(), request.getStatus(), request.getAttempts(), request.getLastError(),
                request.getCreatedAt(), request.getUpdatedAt());
    }
}
