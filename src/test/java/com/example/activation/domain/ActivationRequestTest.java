package com.example.activation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");

    @Test
    void newRequestStartsPendingAndDueImmediately() {
        var request = newRequest();

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void successfulFlowGoesThroughProcessingToCompleted() {
        var request = newRequest();

        request.startProcessing(NOW.plusSeconds(1));
        request.complete(NOW.plusSeconds(2));

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void temporaryFailureReturnsToPendingKeepingTheAttemptCount() {
        var request = newRequest();
        request.startProcessing(NOW);

        request.scheduleRetry("HTTP 500", NOW.plusSeconds(30), NOW);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(request.getLastError()).isEqualTo("HTTP 500");
    }

    @Test
    void cannotCompleteWithoutProcessing() {
        var request = newRequest();

        assertThatThrownBy(() -> request.complete(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING -> COMPLETED");
    }

    @Test
    void completedIsFinal() {
        var request = newRequest();
        request.startProcessing(NOW);
        request.complete(NOW);

        assertThatThrownBy(() -> request.startProcessing(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.fail("x", NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void failedIsFinal() {
        var request = newRequest();
        request.startProcessing(NOW);
        request.fail("HTTP 400", NOW);

        assertThatThrownBy(() -> request.scheduleRetry("x", NOW, NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void comparesContentForRetryDetection() {
        var request = newRequest();

        assertThat(request.hasSameContentAs("CUST-10001", "INTERNET-500")).isTrue();
        assertThat(request.hasSameContentAs("CUST-10001", "INTERNET-1000")).isFalse();
    }

    private static ActivationRequest newRequest() {
        return ActivationRequest.create("client-a", "EXT-12345", "CUST-10001", "INTERNET-500", "corr-1", NOW);
    }
}
