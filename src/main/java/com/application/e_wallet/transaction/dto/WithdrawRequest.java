package com.application.e_wallet.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** The customer withdraws at an agent: the agent's till number, the amount, and the customer's own PIN. */
public record WithdrawRequest(
        @NotBlank(message = "The agent's till number is required")
        @Pattern(regexp = "^[\\d ]{6,8}$", message = "A till number has 6 digits")
        String tillNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        @NotBlank(message = "Your PIN is required")
        @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
        String pin
) {
}
