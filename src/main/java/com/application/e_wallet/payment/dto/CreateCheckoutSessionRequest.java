package com.application.e_wallet.payment.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateCheckoutSessionRequest(
        @NotNull
        @DecimalMin(value = "1000.00", message = "Minimum top-up amount is 1,000 TZS")
        @DecimalMax(value = "999999.99", message = "Maximum top-up amount is 999,999.99")
        @Digits(integer = 6, fraction = 2, message = "Amount must have at most 2 decimal places")
        BigDecimal amount
) {
}
