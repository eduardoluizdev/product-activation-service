package com.example.activation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispara o processamento periodicamente. Desligado nos testes para que eles controlem o ritmo. */
@Component
@ConditionalOnProperty(value = "activation.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationScheduler {

    private final ActivationProcessor processor;

    public ActivationScheduler(ActivationProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${activation.worker.poll-interval:2s}")
    public void processDueRequests() {
        processor.processDueRequests();
    }

    @Scheduled(fixedDelayString = "${activation.worker.stuck-check-interval:30s}")
    public void releaseStuckRequests() {
        processor.releaseStuckRequests();
    }
}
