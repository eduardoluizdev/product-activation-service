package com.example.activation.application;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backoff exponencial com jitter: base * 2^(tentativa-1), limitado a {@code maxDelay},
 * sorteado entre 50% e 100% do valor para que retries não sincronizem.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public static RetryPolicy from(WorkerProperties properties) {
        return new RetryPolicy(properties.maxAttempts(), properties.baseBackoff(), properties.maxBackoff());
    }

    public boolean canRetry(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    /**
     * @param retryAfter quando o provedor informa Retry-After, ele é respeitado (até {@code maxDelay})
     */
    public Duration nextDelay(int attemptsSoFar, Duration retryAfter) {
        if (retryAfter != null) {
            return min(retryAfter, maxDelay);
        }
        int exponent = Math.min(Math.max(attemptsSoFar - 1, 0), 20);
        Duration exponential = min(baseDelay.multipliedBy(1L << exponent), maxDelay);
        double jitter = ThreadLocalRandom.current().nextDouble(0.5, 1.0);
        return Duration.ofMillis(Math.round(exponential.toMillis() * jitter));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
