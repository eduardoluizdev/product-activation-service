package com.example.activation.api;

import com.example.activation.application.ActivationRequestNotFoundException;
import com.example.activation.application.ExternalIdConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Converte exceções em Problem Details (RFC 9457). Nada interno (stack trace, SQL, nome de classe)
 * chega ao consumidor; o {@code traceId} liga a resposta ao log.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ActivationRequestNotFoundException.class)
    ProblemDetail handleNotFound(ActivationRequestNotFoundException e, HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Solicitação não encontrada",
                e.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ExternalIdConflictException.class)
    ProblemDetail handleConflict(ExternalIdConflictException e, HttpServletRequest request) {
        var problem = ProblemDetails.of(HttpStatus.CONFLICT, ProblemTypes.DUPLICATE_EXTERNAL_ID,
                "Solicitação duplicada", e.getMessage(), request.getRequestURI());
        problem.setProperty("externalId", e.getExternalId());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Erro inesperado em {} {}", request.getMethod(), request.getRequestURI(), e);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Informe o traceId ao suporte.", request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "inválido" : error.getDefaultMessage()))
                .toList();
        var problem = e.getBody();
        problem.setType(ProblemTypes.VALIDATION_ERROR);
        problem.setTitle("Dados inválidos");
        problem.setDetail("Um ou mais campos não passaram na validação.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    /** Ponto único por onde passam as respostas das exceções padrão do Spring MVC. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemDetails.withTraceId(problem);
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }
}
