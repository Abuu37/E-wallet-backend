package com.application.e_wallet.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateCheckoutSessionRequest(
        @NotNull @DecimalMin(value = "1000.00", message = "Minimum top-up amount is 1,000 TZS") BigDecimal amount
) {
}
