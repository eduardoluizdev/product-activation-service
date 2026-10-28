package com.example.activation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("activation.worker")
public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("2s") Duration baseBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("2m") Duration stuckTimeout,
        @DefaultValue("30s") Duration stuckCheckInterval) {
}
