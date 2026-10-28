package com.example.activation.application;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(2), Duration.ofMinutes(1));

    @Test
    void allowsRetryUntilMaxAttempts() {
        assertThat(policy.canRetry(4)).isTrue();
        assertThat(policy.canRetry(5)).isFalse();
    }

    @RepeatedTest(20)
    void delayGrowsExponentiallyWithJitter() {
        assertThat(policy.nextDelay(1, null)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(policy.nextDelay(3, null)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void delayIsCappedAtMax() {
        assertThat(policy.nextDelay(30, null)).isLessThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void retryAfterFromProviderWins() {
        assertThat(policy.nextDelay(1, Duration.ofSeconds(17))).isEqualTo(Duration.ofSeconds(17));
        assertThat(policy.nextDelay(1, Duration.ofHours(1))).isEqualTo(Duration.ofMinutes(1));
    }
}
