package com.example.activation.integration;

import com.example.activation.application.ActivationRequestService;
import com.example.activation.application.CreationResult;
import com.example.activation.application.NewActivationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** A garantia de unicidade precisa valer com o banco real; H2 ou mocks não provariam nada aqui. */
class ConcurrentDuplicateIT extends AbstractIntegrationTest {

    private static final int THREADS = 10;

    @Autowired
    private ActivationRequestService service;

    @Test
    void parallelRequestsWithSameExternalIdCreateExactlyOneRecord() throws Exception {
        var command = new NewActivationRequest("client-a", "EXT-RACE", "CUST-1", "INTERNET-500");
        var startTogether = new CountDownLatch(1);
        List<Future<CreationResult>> futures = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                Callable<CreationResult> task = () -> {
                    startTogether.await();
                    return service.create(command);
                };
                futures.add(executor.submit(task));
            }
            startTogether.countDown();

            List<CreationResult> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }

            assertThat(results).filteredOn(CreationResult::created).hasSize(1);
            assertThat(results).extracting(result -> result.request().getId()).containsOnly(
                    results.getFirst().request().getId());
        }
        assertThat(repository.count()).isEqualTo(1);
    }
}
