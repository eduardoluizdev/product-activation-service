package com.example.activation.api;

import com.example.activation.api.security.ClientIdentity;
import com.example.activation.application.ActivationRequestService;
import com.example.activation.application.NewActivationRequest;
import com.example.activation.domain.ActivationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    /**
     * 201 quando cria; 200 com o registro existente quando é um retry com o mesmo conteúdo;
     * 409 quando o externalId já foi usado com outro conteúdo.
     */
    @PostMapping
    public ResponseEntity<ActivationResponse> create(@Valid @RequestBody CreateActivationPayload payload,
                                                     @AuthenticationPrincipal Jwt jwt) {
        var result = service.create(new NewActivationRequest(ClientIdentity.of(jwt), payload.externalId(),
                payload.customerId(), payload.productCode()));
        var body = ActivationResponse.from(result.request());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{id}")
    public ActivationResponse findById(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ActivationResponse.from(service.findById(id, ClientIdentity.of(jwt)));
    }

    /** Ordenação fixa (mais recentes primeiro) para casar com os índices; o cliente não escolhe a coluna. */
    @GetMapping
    public PageResponse<ActivationResponse> search(@RequestParam(required = false) ActivationStatus status,
                                                   @RequestParam(required = false) String customerId,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                   @AuthenticationPrincipal Jwt jwt) {
        var result = service.search(ClientIdentity.of(jwt), status, customerId, page, size);
        return PageResponse.from(result, ActivationResponse::from);
    }
}
