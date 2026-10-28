package com.example.activation.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    /** Lock timeout -2 = SKIP LOCKED: instâncias concorrentes não pegam as mesmas linhas. */
    String SKIP_LOCKED = "-2";

    Optional<ActivationRequest> findByClientIdAndExternalId(String clientId, String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query("""
            select r from ActivationRequest r
            where r.status = com.example.activation.domain.ActivationStatus.PENDING
              and r.nextAttemptAt <= :now
            order by r.nextAttemptAt
            """)
    List<ActivationRequest> lockDueForProcessing(@Param("now") Instant now, Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query("""
            select r from ActivationRequest r
            where r.status = com.example.activation.domain.ActivationStatus.PROCESSING
              and r.updatedAt < :cutoff
            order by r.updatedAt
            """)
    List<ActivationRequest> lockStuckInProcessing(@Param("cutoff") Instant cutoff, Limit limit);
}
