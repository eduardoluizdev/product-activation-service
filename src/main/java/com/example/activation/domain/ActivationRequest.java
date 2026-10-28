package com.example.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de ativação de produto. O status só muda pelos métodos de domínio,
 * que validam a transição; não há setters públicos.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ActivationRequest() {
        // JPA
    }

    public static ActivationRequest create(String clientId, String externalId, String customerId,
                                           String productCode, String correlationId, Instant now) {
        var request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.clientId = Objects.requireNonNull(clientId);
        request.externalId = Objects.requireNonNull(externalId);
        request.customerId = Objects.requireNonNull(customerId);
        request.productCode = Objects.requireNonNull(productCode);
        request.correlationId = correlationId;
        request.status = ActivationStatus.PENDING;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /** Um retry legítimo do sistema de origem traz exatamente o mesmo conteúdo. */
    public boolean hasSameContentAs(String otherCustomerId, String otherProductCode) {
        return customerId.equals(otherCustomerId) && productCode.equals(otherProductCode);
    }

    public void startProcessing(Instant now) {
        moveTo(ActivationStatus.PROCESSING, now);
        attempts++;
        nextAttemptAt = null;
    }

    public void complete(Instant now) {
        moveTo(ActivationStatus.COMPLETED, now);
        lastError = null;
    }

    public void fail(String reason, Instant now) {
        moveTo(ActivationStatus.FAILED, now);
        lastError = truncate(reason);
    }

    public void scheduleRetry(String reason, Instant nextAttemptAt, Instant now) {
        moveTo(ActivationStatus.PENDING, now);
        this.lastError = truncate(reason);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
    }

    private void moveTo(ActivationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        status = target;
        updatedAt = now;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public ActivationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
