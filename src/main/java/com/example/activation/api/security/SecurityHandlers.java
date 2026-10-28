package com.example.activation.api.security;

import com.example.activation.api.ProblemDetails;
import com.example.activation.api.ProblemTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URI;

/** Respostas 401 e 403 no mesmo formato Problem Details do restante da API. */
class SecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    SecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Não autenticado",
                "Envie um token Bearer válido.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        write(request, response, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Acesso negado",
                "O token não possui o escopo necessário para esta operação.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       URI type, String title, String detail) throws IOException {
        ProblemDetail problem = ProblemDetails.of(status, type, title, detail, request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
