package com.example.activation.application;

import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.ActivationRequestRepository;
import com.example.activation.domain.ActivationStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Envia as solicitações pendentes ao provedor.
 * <p>
 * Cada envio usa duas transações curtas e a chamada HTTP fica entre elas, fora de qualquer
 * transação: (1) trava um lote de PENDING com SKIP LOCKED e marca PROCESSING; (2) após a
 * resposta, grava o novo status. Se a aplicação cair no meio, a solicitação fica em
 * PROCESSING e é devolvida para a fila por {@link #releaseStuckRequests()}.
 */
@Service
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final WorkerProperties properties;
    private final RetryPolicy retryPolicy;
    private final MeterRegistry meterRegistry;

    public ActivationProcessor(ActivationRequestRepository repository, ActivationProvider provider,
                               TransactionTemplate transaction, Clock clock, WorkerProperties properties,
                               MeterRegistry meterRegistry) {
        this.repository = repository;
        this.provider = provider;
        this.transaction = transaction;
        this.clock = clock;
        this.properties = properties;
        this.retryPolicy = RetryPolicy.from(properties);
        this.meterRegistry = meterRegistry;
    }

    /** @return quantidade de solicitações enviadas ao provedor nesta rodada */
    public int processDueRequests() {
        List<ActivationRequest> claimed = transaction.execute(status -> claimDueRequests());
        claimed.forEach(this::activate);
        return claimed.size();
    }

    /** Devolve para PENDING (ou FAILED, se esgotou as tentativas) o que ficou preso em PROCESSING. */
    public int releaseStuckRequests() {
        return transaction.execute(status -> {
            Instant now = now();
            var stuck = repository.lockStuckInProcessing(now.minus(properties.stuckTimeout()),
                    Limit.of(properties.batchSize()));
            stuck.forEach(request -> {
                log.warn("Solicitação {} presa em PROCESSING desde {}", request.getId(), request.getUpdatedAt());
                retryOrFail(request, "Sem resposta registrada do processamento anterior", null, now);
                recordTransition(request);
            });
            return stuck.size();
        });
    }

    private List<ActivationRequest> claimDueRequests() {
        Instant now = now();
        var due = repository.lockDueForProcessing(now, Limit.of(properties.batchSize()));
        due.forEach(request -> request.startProcessing(now));
        return due;
    }

    private void activate(ActivationRequest request) {
        CorrelationContext.runWith(request.getCorrelationId(), () -> {
            ProviderResult result = callProvider(request);
            try {
                transaction.executeWithoutResult(status -> applyResult(request.getId(), result));
            } catch (OptimisticLockingFailureException e) {
                log.warn("Solicitação {} alterada por outro processo; resultado descartado", request.getId());
            }
        });
    }

    private ProviderResult callProvider(ActivationRequest request) {
        try {
            return provider.activate(request);
        } catch (RuntimeException e) {
            log.error("Erro inesperado ao chamar o provedor para {}", request.getId(), e);
            return new ProviderResult.TransientFailure("Erro inesperado na integração");
        }
    }

    private void applyResult(UUID id, ProviderResult result) {
        var request = repository.findById(id).orElseThrow();
        if (request.getStatus() != ActivationStatus.PROCESSING) {
            log.warn("Solicitação {} não está mais em PROCESSING ({}); resultado ignorado", id, request.getStatus());
            return;
        }
        Instant now = now();
        switch (result) {
            case ProviderResult.Success success -> request.complete(now);
            case ProviderResult.PermanentFailure failure -> request.fail(failure.reason(), now);
            case ProviderResult.TransientFailure failure ->
                    retryOrFail(request, failure.reason(), failure.retryAfter(), now);
        }
        recordTransition(request);
        log.info("Solicitação {} -> {} (tentativa {})", id, request.getStatus(), request.getAttempts());
    }

    private void retryOrFail(ActivationRequest request, String reason, Duration retryAfter, Instant now) {
        if (retryPolicy.canRetry(request.getAttempts())) {
            Duration delay = retryPolicy.nextDelay(request.getAttempts(), retryAfter);
            request.scheduleRetry(reason, now.plus(delay), now);
        } else {
            request.fail("Limite de %d tentativas atingido. Último erro: %s"
                    .formatted(request.getAttempts(), reason), now);
        }
    }

    private void recordTransition(ActivationRequest request) {
        meterRegistry.counter("activation.requests.transitions", "status", request.getStatus().name())
                .increment();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
