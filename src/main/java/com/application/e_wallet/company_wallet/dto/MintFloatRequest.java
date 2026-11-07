package com.application.e_wallet.company_wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record MintFloatRequest(
        @NotNull
        @Positive
        BigDecimal amount,

        @NotBlank
        String description
) {
}
