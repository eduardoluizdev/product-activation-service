package com.example.activation.integration;

import com.example.activation.domain.ActivationRequestRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Arrays;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Sobe a aplicação completa com PostgreSQL real (Testcontainers) e o provedor simulado (WireMock).
 * Os contêineres são iniciados uma vez e compartilhados por todas as classes de teste.
 * O worker agendado fica desligado: os testes chamam o processador diretamente.
 */
@SpringBootTest(properties = {
        "activation.worker.enabled=false",
        "activation.worker.max-attempts=3",
        "activation.provider.read-timeout=500ms",
        "activation.security.local-jwt.hmac-secret=integration-test-secret-with-32-bytes-or-more"
})
@AutoConfigureMockMvc
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        POSTGRES.start();
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("activation.provider.base-url", PROVIDER::baseUrl);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ActivationRequestRepository repository;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        PROVIDER.resetAll();
        jdbc.update("delete from activation_request");
    }

    protected static RequestPostProcessor client(String clientId, String... scopes) {
        GrantedAuthority[] authorities = Arrays.stream(scopes)
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toArray(GrantedAuthority[]::new);
        return jwt().jwt(j -> j.claim("client_id", clientId)).authorities(authorities);
    }

    protected static RequestPostProcessor fullAccess(String clientId) {
        return client(clientId, "activation:read", "activation:write");
    }
}
