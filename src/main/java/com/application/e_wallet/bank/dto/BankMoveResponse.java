package com.application.e_wallet.bank.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BankMoveResponse(
        String reference,
        /** COMPLETED for a top-up. PENDING for a withdrawal until the bank confirms, then COMPLETED or FAILED. */
        String status,
        String currency,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal total,
        String bank,
        String nameCheck,
        BigDecimal balanceAfter,
        OffsetDateTime createdAt
) {
}
