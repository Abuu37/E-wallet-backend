package com.application.e_wallet.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CashInRequest(
        @NotBlank(message = "Agent account number is required")
        String agentAccountNumber,

        @NotBlank(message = "Customer account number is required")
        String accountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "1000.00", message = "Minimum cash-in amount is 1,000 TZS")
        BigDecimal amount,

        @NotBlank(message = "Customer phone number is required")
        String phone,

        @NotBlank(message = "Agent pin is required")
        String agentPin
) {
}
