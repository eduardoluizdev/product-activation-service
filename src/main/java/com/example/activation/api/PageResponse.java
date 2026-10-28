package com.example.activation.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Envelope estável de paginação (não expõe a serialização interna do {@link Page}). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.map(mapper).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
