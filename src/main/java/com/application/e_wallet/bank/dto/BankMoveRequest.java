package com.application.e_wallet.bank.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

/** Add money from, or withdraw to, the person's own linked bank. The PIN approves it. */
public record BankMoveRequest(
        @NotNull(message = "Choose a bank") UUID linkedBankId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "1000", message = "The smallest amount is 1,000 TZS")
        BigDecimal amount,

        @NotBlank(message = "Your PIN is required")
        @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
        String pin
) {
}
