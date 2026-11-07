package com.application.e_wallet.company_wallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record AllocateFloatRequest(
        @NotNull
        UUID agentId,

        @NotNull
        @Positive
        BigDecimal amount,

        @NotBlank
        String description
) {
}
