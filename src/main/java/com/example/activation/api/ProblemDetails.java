package com.example.activation.api;

import com.example.activation.application.CorrelationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class ProblemDetails {

    public static final String TRACE_ID = "traceId";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatusCode status, URI type, String title, String detail, String instance) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        return withTraceId(problem);
    }

    /** Inclui o id de correlação para o consumidor reportar e o time achar o erro no log. */
    public static ProblemDetail withTraceId(ProblemDetail problem) {
        CorrelationContext.current().ifPresent(id -> problem.setProperty(TRACE_ID, id));
        return problem;
    }
}
