package com.application.e_wallet.treasury.dto;

import com.application.e_wallet.treasury.entity.RequestType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TreasuryRequestCreateRequest(

        @NotNull
        RequestType requestType,

        @NotNull
        @Positive
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,

        @NotBlank
        String reason,

        String externalReference

) {
}
