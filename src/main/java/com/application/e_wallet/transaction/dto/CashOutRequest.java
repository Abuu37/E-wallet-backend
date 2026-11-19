package com.application.e_wallet.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CashOutRequest(
        @NotBlank(message = "Agent account number is required")
        String agentAccountNumber,

        @NotBlank(message = "Agent pin is required")
        String agentPin,

        @NotBlank(message = "Customer account number is required")
        String customerAccountNumber,

        @NotBlank(message = "Customer phone number is required")
        String phone,

        @NotBlank(message = "Customer pin is required")
        String customerPin,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "1000.00", message = "Minimum cash-out amount is 1,000 TZS")
        BigDecimal amount
) {
}
