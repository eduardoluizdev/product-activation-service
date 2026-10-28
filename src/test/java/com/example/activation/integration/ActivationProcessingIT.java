package com.example.activation.integration;

import com.example.activation.application.ActivationProcessor;
import com.example.activation.application.ActivationRequestService;
import com.example.activation.application.NewActivationRequest;
import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.ActivationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Integração com o provedor: status final para cada tipo de retorno. */
class ActivationProcessingIT extends AbstractIntegrationTest {

    private static final String ACTIVATE = "/provider/v1/activate";

    @Autowired
    private ActivationRequestService service;

    @Autowired
    private ActivationProcessor processor;

    @Test
    void successCompletesRequestAndSendsIdempotencyKey() {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200)));
        UUID id = newRequest();

        assertThat(processor.processDueRequests()).isEqualTo(1);

        assertThat(reload(id).getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        PROVIDER.verify(postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key", equalTo(id.toString())));
    }

    @Test
    void badRequestFailsWithoutRetry() {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(400)));
        UUID id = newRequest();

        processor.processDueRequests();

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getLastError()).contains("HTTP 400");
    }

    @Test
    void serverErrorSchedulesRetry() {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(500)));
        UUID id = newRequest();

        processor.processDueRequests();

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(processor.processDueRequests()).as("ainda não chegou a hora do retry").isZero();
    }

    @Test
    void timeoutSchedulesRetry() {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(200).withFixedDelay(1_500)));
        UUID id = newRequest();

        processor.processDueRequests();

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getLastError()).contains("timeout");
    }

    @Test
    void failsAfterMaxAttempts() {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(503)));
        UUID id = newRequest();

        for (int attempt = 1; attempt <= 3; attempt++) {
            makeDueNow(id);
            processor.processDueRequests();
        }

        var request = reload(id);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(3);
        PROVIDER.verify(3, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void retryAfterTransientFailureCanSucceed() {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(500)));
        UUID id = newRequest();
        processor.processDueRequests();

        PROVIDER.resetAll();
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(201)));
        makeDueNow(id);
        processor.processDueRequests();

        assertThat(reload(id).getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(reload(id).getAttempts()).isEqualTo(2);
    }

    @Test
    void requestStuckInProcessingIsReleased() {
        UUID id = newRequest();
        jdbc.update("update activation_request set status = 'PROCESSING', attempts = 1, "
                + "updated_at = now() - interval '1 hour' where id = ?", id);

        assertThat(processor.releaseStuckRequests()).isEqualTo(1);

        assertThat(reload(id).getStatus()).isEqualTo(ActivationStatus.PENDING);
    }

    private UUID newRequest() {
        return service.create(new NewActivationRequest("client-a", "EXT-" + UUID.randomUUID(), "CUST-1",
                "INTERNET-500")).request().getId();
    }

    private void makeDueNow(UUID id) {
        jdbc.update("update activation_request set next_attempt_at = now() - interval '1 second' where id = ?", id);
    }

    private ActivationRequest reload(UUID id) {
        return repository.findById(id).orElseThrow();
    }
}
