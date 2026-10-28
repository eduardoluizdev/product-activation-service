package com.example.activation.application;

import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.ActivationRequestRepository;
import com.example.activation.domain.ActivationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Cria a solicitação ou devolve a já existente quando é um retry com o mesmo conteúdo.
     * <p>
     * Propositalmente sem {@code @Transactional}: o insert roda na sua própria transação.
     * Se duas requisições iguais chegarem juntas, as duas passam pela consulta inicial, mas só
     * uma passa pela constraint única do banco; a outra recebe a violação, e como aquela
     * transação já foi desfeita, conseguimos ler o registro vencedor em uma nova transação.
     */
    public CreationResult create(NewActivationRequest command) {
        var existing = repository.findByClientIdAndExternalId(command.clientId(), command.externalId());
        if (existing.isPresent()) {
            return asRetryOf(existing.get(), command);
        }

        var request = ActivationRequest.create(command.clientId(), command.externalId(), command.customerId(),
                command.productCode(), CorrelationContext.current().orElse(null), now());
        try {
            var saved = repository.saveAndFlush(request);
            log.info("Solicitação criada id={} externalId={}", saved.getId(), saved.getExternalId());
            return new CreationResult(saved, true);
        } catch (DataIntegrityViolationException e) {
            var winner = repository.findByClientIdAndExternalId(command.clientId(), command.externalId())
                    .orElseThrow(() -> e);
            log.info("Criação concorrente para externalId={}, devolvendo registro existente", command.externalId());
            return asRetryOf(winner, command);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest findById(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> search(String clientId, ActivationStatus status, String customerId,
                                          int page, int size) {
        Specification<ActivationRequest> spec = (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (customerId != null && !customerId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findAll(spec, pageable);
    }

    private CreationResult asRetryOf(ActivationRequest existing, NewActivationRequest command) {
        if (!existing.hasSameContentAs(command.customerId(), command.productCode())) {
            throw new ExternalIdConflictException(command.externalId());
        }
        return new CreationResult(existing, false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
