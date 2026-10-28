package com.example.activation.infrastructure.provider;

import com.example.activation.application.ProviderResult;
import com.example.activation.domain.ActivationRequest;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Testa o cliente HTTP isolado (sem Spring): mapeamento de cada retorno do provedor. */
class HttpActivationProviderTest {

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private HttpActivationProvider client;
    private final ActivationRequest request = ActivationRequest.create(
            "client-a", "EXT-1", "CUST-1", "INTERNET-500", "corr-1", Instant.now());

    @BeforeEach
    void setUp() {
        var properties = new ProviderProperties(provider.baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(300));
        RestClient restClient = ProviderClientConfig.createRestClient(RestClient.builder(), properties);
        client = new HttpActivationProvider(restClient, new SimpleMeterRegistry());
    }

    @Test
    void successSendsIdempotencyKey() {
        provider.stubFor(post(HttpActivationProvider.ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));

        var result = client.activate(request);

        assertThat(result).isInstanceOf(ProviderResult.Success.class);
        provider.verify(postRequestedFor(urlEqualTo(HttpActivationProvider.ACTIVATE_PATH))
                .withHeader("Idempotency-Key", equalTo(request.getId().toString()))
                .withRequestBody(equalToJson("""
                        {"externalId": "EXT-1", "customerId": "CUST-1", "productCode": "INTERNET-500"}
                        """, true, true)));
    }

    @Test
    void badRequestIsPermanent() {
        provider.stubFor(post(HttpActivationProvider.ACTIVATE_PATH).willReturn(aResponse().withStatus(400)));

        assertThat(client.activate(request)).isInstanceOf(ProviderResult.PermanentFailure.class);
    }

    @Test
    void serverErrorIsTransient() {
        provider.stubFor(post(HttpActivationProvider.ACTIVATE_PATH).willReturn(aResponse().withStatus(500)));

        assertThat(client.activate(request)).isInstanceOf(ProviderResult.TransientFailure.class);
    }

    @Test
    void serviceUnavailableHonoursRetryAfter() {
        provider.stubFor(post(HttpActivationProvider.ACTIVATE_PATH)
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "30")));

        var result = client.activate(request);

        assertThat(result).isEqualTo(new ProviderResult.TransientFailure(
                "Provedor indisponível (HTTP 503)", Duration.ofSeconds(30)));
    }

    @Test
    void tooManyRequestsIsTransient() {
        provider.stubFor(post(HttpActivationProvider.ACTIVATE_PATH).willReturn(aResponse().withStatus(429)));

        assertThat(client.activate(request)).isInstanceOf(ProviderResult.TransientFailure.class);
    }

    @Test
    void readTimeoutIsTransient() {
        provider.stubFor(post(HttpActivationProvider.ACTIVATE_PATH)
                .willReturn(aResponse().withStatus(200).withFixedDelay(1_000)));

        assertThat(client.activate(request)).isInstanceOf(ProviderResult.TransientFailure.class);
    }
}
