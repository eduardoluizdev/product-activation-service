package com.example.activation.domain;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(ActivationStatus from, ActivationStatus to) {
        super("Transição de status inválida: %s -> %s".formatted(from, to));
    }
}
