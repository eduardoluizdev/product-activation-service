package com.example.activation.application;

import java.time.Duration;

/** Resultado de uma chamada ao provedor, já classificado para decidir o próximo status. */
public sealed interface ProviderResult {

    record Success() implements ProviderResult {
    }

    /** O provedor recusou a solicitação; tentar de novo daria o mesmo resultado. */
    record PermanentFailure(String reason) implements ProviderResult {
    }

    /**
     * Falha que pode passar sozinha (5xx, timeout, 429).
     *
     * @param retryAfter valor do header Retry-After, ou {@code null} quando ausente
     */
    record TransientFailure(String reason, Duration retryAfter) implements ProviderResult {

        public TransientFailure(String reason) {
            this(reason, null);
        }
    }
}
