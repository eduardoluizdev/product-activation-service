package com.example.activation.api;

import com.example.activation.api.security.SecurityConfig;
import com.example.activation.application.ActivationRequestNotFoundException;
import com.example.activation.application.ActivationRequestService;
import com.example.activation.application.CreationResult;
import com.example.activation.application.ExternalIdConflictException;
import com.example.activation.application.NewActivationRequest;
import com.example.activation.domain.ActivationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Camada web isolada: contrato HTTP, validação, formato de erro e regras de acesso. */
@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfig.class)
class ActivationRequestControllerTest {

    private static final String VALID_BODY = """
            {"externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivationRequestService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void createsRequestAndReturnsLocation() throws Exception {
        var created = sampleRequest();
        given(service.create(any())).willReturn(new CreationResult(created, true));

        mockMvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/requests/" + created.getId())))
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"));
    }

    @Test
    void retryWithSameContentReturnsOkWithExistingRecord() throws Exception {
        given(service.create(any())).willReturn(new CreationResult(sampleRequest(), false));

        mockMvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void usesClientIdFromTokenAsOwner() throws Exception {
        given(service.create(new NewActivationRequest("erp", "EXT-12345", "CUST-10001", "INTERNET-500")))
                .willReturn(new CreationResult(sampleRequest(), true));

        mockMvc.perform(post("/api/v1/requests")
                        .with(jwt().jwt(j -> j.claim("client_id", "erp"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_activation:write")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsInvalidPayloadWithProblemDetails() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId": "EXT 1", "productCode": "INTERNET-500"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.type").value(ProblemTypes.VALIDATION_ERROR.toString()))
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("é obrigatório"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    @Test
    void divergentDuplicateReturnsConflict() throws Exception {
        given(service.create(any())).willThrow(new ExternalIdConflictException("EXT-12345"));

        mockMvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ProblemTypes.DUPLICATE_EXTERNAL_ID.toString()))
                .andExpect(jsonPath("$.externalId").value("EXT-12345"));
    }

    @Test
    void returnsRequestById() throws Exception {
        var request = sampleRequest();
        given(service.findById(eq(request.getId()), any())).willReturn(request);

        mockMvc.perform(get("/api/v1/requests/{id}", request.getId()).with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"));
    }

    @Test
    void unknownIdReturnsNotFound() throws Exception {
        var id = UUID.randomUUID();
        given(service.findById(eq(id), any())).willThrow(new ActivationRequestNotFoundException(id));

        mockMvc.perform(get("/api/v1/requests/{id}", id).with(reader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemTypes.NOT_FOUND.toString()))
                .andExpect(jsonPath("$.instance").value("/api/v1/requests/" + id));
    }

    @Test
    void invalidUuidReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/requests/not-a-uuid").with(reader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeAboveLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/requests").param("size", "500").with(reader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedErrorDoesNotLeakDetails() throws Exception {
        given(service.create(any())).willThrow(new IllegalStateException("org.postgresql: connection refused"));

        mockMvc.perform(post("/api/v1/requests").with(writer()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Ocorreu um erro inesperado. Informe o traceId ao suporte."));
    }

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.type").value(ProblemTypes.UNAUTHORIZED.toString()));
    }

    @Test
    void readOnlyClientCannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/requests").with(reader()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(ProblemTypes.FORBIDDEN.toString()));
        verifyNoInteractions(service);
    }

    @Test
    void writeOnlyClientCannotRead() throws Exception {
        mockMvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(writer()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor writer() {
        return jwt().jwt(j -> j.claim("client_id", "client-a"))
                .authorities(new SimpleGrantedAuthority("SCOPE_activation:write"));
    }

    private static RequestPostProcessor reader() {
        return jwt().jwt(j -> j.claim("client_id", "client-a"))
                .authorities(new SimpleGrantedAuthority("SCOPE_activation:read"));
    }

    private static ActivationRequest sampleRequest() {
        return ActivationRequest.create("client-a", "EXT-12345", "CUST-10001", "INTERNET-500", "corr",
                Instant.parse("2026-10-26T14:03:11Z"));
    }
}
