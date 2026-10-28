package com.example.activation.application;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * Identificador de correlação guardado no MDC. Ele é gravado junto com a solicitação para
 * que o processamento assíncrono continue logando (e enviando ao provedor) o mesmo id.
 */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    public static void runWith(String correlationId, Runnable action) {
        String previous = MDC.get(MDC_KEY);
        if (correlationId != null) {
            MDC.put(MDC_KEY, correlationId);
        }
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
