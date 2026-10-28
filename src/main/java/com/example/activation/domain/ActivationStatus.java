package com.example.activation.domain;

/**
 * Ciclo de vida de uma solicitação de ativação.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED
 *    ▲            │
 *    └────────────┤ (falha temporária, com contador de tentativas)
 *                 └────────► FAILED
 * </pre>
 */
public enum ActivationStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(ActivationStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == COMPLETED || target == FAILED || target == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
