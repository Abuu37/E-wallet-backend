package com.application.e_wallet.treasury.operations;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Ask for a treasury move. For a reversal, give the transfer's reference; its amount is taken from the transfer. */
public record OperationRequest(
        @NotNull(message = "Choose what you want to do") OperationType type,
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero") BigDecimal amount,
        @NotBlank(message = "Say why") @Size(max = 500, message = "Keep the reason under 500 characters") String reason,
        @Size(max = 60) String targetReference
) {
}
