package com.example.activation.application;

import com.example.activation.domain.ActivationRequest;

/** Porta para o provedor externo de ativação. A implementação HTTP fica em infrastructure. */
public interface ActivationProvider {

    /**
     * Nunca lança exceção para falhas esperadas (HTTP, timeout, conexão): elas viram {@link ProviderResult}.
     */
    ProviderResult activate(ActivationRequest request);
}
