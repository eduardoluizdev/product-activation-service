package com.example.activation.infrastructure.provider;

import com.example.activation.application.ActivationProvider;
import com.example.activation.application.CorrelationContext;
import com.example.activation.application.ProviderResult;
import com.example.activation.domain.ActivationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Chamada {@code POST /provider/v1/activate}. O id da solicitação vai como Idempotency-Key,
 * então um retry após timeout não gera uma segunda ativação no provedor.
 */
@Component
public class HttpActivationProvider implements ActivationProvider {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public HttpActivationProvider(RestClient providerRestClient, MeterRegistry meterRegistry) {
        this.restClient = providerRestClient;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ProviderResult activate(ActivationRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ProviderResult result = send(request);
        sample.stop(meterRegistry.timer("activation.provider.calls", "outcome", outcomeTag(result)));
        return result;
    }

    private ProviderResult send(ActivationRequest request) {
        var payload = new ProviderActivationPayload(request.getId().toString(), request.getExternalId(),
                request.getCustomerId(), request.getProductCode());
        try {
            restClient.post()
                    .uri(ACTIVATE_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, request.getId().toString())
                    .headers(headers -> CorrelationContext.current()
                            .ifPresent(id -> headers.set(CORRELATION_ID_HEADER, id)))
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return new ProviderResult.Success();
        } catch (RestClientResponseException e) {
            log.warn("Provedor respondeu HTTP {} para {}", e.getStatusCode().value(), request.getId());
            return classify(e.getStatusCode(), e.getResponseHeaders());
        } catch (ResourceAccessException e) {
            // Timeout de leitura, conexão recusada etc. Em timeout o provedor pode ter ativado:
            // o retry é seguro porque reenviamos a mesma Idempotency-Key.
            log.warn("Falha de comunicação com o provedor para {}: {}", request.getId(), e.getMessage());
            return new ProviderResult.TransientFailure("Falha de comunicação com o provedor (timeout ou conexão)");
        }
    }

    static ProviderResult classify(HttpStatusCode status, HttpHeaders headers) {
        int code = status.value();
        if (code == HttpStatus.TOO_MANY_REQUESTS.value() || code == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return new ProviderResult.TransientFailure("Provedor indisponível (HTTP %d)".formatted(code),
                    parseRetryAfter(headers));
        }
        if (code == HttpStatus.REQUEST_TIMEOUT.value() || status.is5xxServerError()) {
            return new ProviderResult.TransientFailure("Erro no provedor (HTTP %d)".formatted(code));
        }
        return new ProviderResult.PermanentFailure("Solicitação recusada pelo provedor (HTTP %d)".formatted(code));
    }

    /** Retry-After pode vir em segundos ou como data HTTP (RFC 9110). */
    static Duration parseRetryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            // tenta como data
        }
        try {
            var date = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            var delay = Duration.between(Instant.now(), date);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String outcomeTag(ProviderResult result) {
        return switch (result) {
            case ProviderResult.Success s -> "success";
            case ProviderResult.PermanentFailure f -> "permanent_failure";
            case ProviderResult.TransientFailure f -> "transient_failure";
        };
    }
}
