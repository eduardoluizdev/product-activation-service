package com.example.activation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Regras: obrigatórios, até 64 caracteres, apenas letras, números, "-" e "_".
 */
public record CreateActivationPayload(
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = CODE_PATTERN, message = "deve conter apenas letras, números, '-' ou '_'")
        String externalId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = CODE_PATTERN, message = "deve conter apenas letras, números, '-' ou '_'")
        String customerId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = CODE_PATTERN, message = "deve conter apenas letras, números, '-' ou '_'")
        String productCode) {

    static final String CODE_PATTERN = "^[A-Za-z0-9_-]*$";
}
