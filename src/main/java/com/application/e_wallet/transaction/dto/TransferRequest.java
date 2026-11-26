package com.application.e_wallet.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** Send money wallet to wallet, by the recipient's phone number. The sender approves with their own PIN. */
public record TransferRequest(
        @NotBlank(message = "The recipient's phone number is required")
        String phone,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        @NotBlank(message = "Your PIN is required")
        @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
        String pin
) {
}
