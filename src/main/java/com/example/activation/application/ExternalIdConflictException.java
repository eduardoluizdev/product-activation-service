package com.example.activation.application;

/** Mesmo externalId reutilizado com conteúdo diferente do registro original. */
public class ExternalIdConflictException extends RuntimeException {

    private final String externalId;

    public ExternalIdConflictException(String externalId) {
        super("Já existe uma solicitação com externalId '%s' e conteúdo diferente".formatted(externalId));
        this.externalId = externalId;
    }

    public String getExternalId() {
        return externalId;
    }
}
