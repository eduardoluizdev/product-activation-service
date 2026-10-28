package com.example.activation.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fluxo HTTP de ponta a ponta contra o banco real. */
class ActivationRequestApiIT extends AbstractIntegrationTest {

    @Test
    void createsAndFetchesRequest() throws Exception {
        String id = create("client-a", "EXT-1", "CUST-1", "INTERNET-500", 201);

        mockMvc.perform(get("/api/v1/requests/{id}", id).with(fullAccess("client-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void sameExternalIdAndContentReturnsExistingRecord() throws Exception {
        String first = create("client-a", "EXT-1", "CUST-1", "INTERNET-500", 201);
        String retry = create("client-a", "EXT-1", "CUST-1", "INTERNET-500", 200);

        assertThat(retry).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentContentIsRejected() throws Exception {
        create("client-a", "EXT-1", "CUST-1", "INTERNET-500", 201);

        mockMvc.perform(post("/api/v1/requests").with(fullAccess("client-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXT-1", "CUST-1", "INTERNET-1000")))
                .andExpect(status().isConflict());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void externalIdIsScopedPerClient() throws Exception {
        String fromA = create("client-a", "EXT-1", "CUST-1", "INTERNET-500", 201);
        String fromB = create("client-b", "EXT-1", "CUST-9", "TV-100", 201);

        assertThat(fromA).isNotEqualTo(fromB);
    }

    @Test
    void clientCannotReadAnotherClientsRequest() throws Exception {
        String id = create("client-a", "EXT-1", "CUST-1", "INTERNET-500", 201);

        mockMvc.perform(get("/api/v1/requests/{id}", id).with(fullAccess("client-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsOnlyOwnRequestsWithFilters() throws Exception {
        create("client-a", "EXT-1", "CUST-1", "INTERNET-500", 201);
        create("client-a", "EXT-2", "CUST-2", "INTERNET-500", 201);
        create("client-b", "EXT-3", "CUST-1", "INTERNET-500", 201);

        mockMvc.perform(get("/api/v1/requests").param("customerId", "CUST-1").param("status", "PENDING")
                        .with(fullAccess("client-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].externalId").value("EXT-1"));
    }

    @Test
    void unknownStatusFilterIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/requests").param("status", "DONE").with(fullAccess("client-a")))
                .andExpect(status().isBadRequest());
    }

    private String create(String clientId, String externalId, String customerId, String productCode,
                          int expectedStatus) throws Exception {
        var response = mockMvc.perform(post("/api/v1/requests").with(fullAccess(clientId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(externalId, customerId, productCode)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static String body(String externalId, String customerId, String productCode) {
        return """
                {"externalId": "%s", "customerId": "%s", "productCode": "%s"}
                """.formatted(externalId, customerId, productCode);
    }
}
