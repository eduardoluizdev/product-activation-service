package com.example.activation.api;

import java.net.URI;

/** URIs de {@code type} dos Problem Details (RFC 9457) devolvidos pela API. */
public final class ProblemTypes {

    private static final String BASE = "https://api.example.com/problems/";

    public static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI DUPLICATE_EXTERNAL_ID = URI.create(BASE + "duplicate-external-id");
    public static final URI UNAUTHORIZED = URI.create(BASE + "unauthorized");
    public static final URI FORBIDDEN = URI.create(BASE + "forbidden");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemTypes() {
    }
}
