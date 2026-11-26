package com.application.e_wallet.transaction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransferResponse(
        String referenceNo,
        String status,
        String currency,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal total,
        String recipientName,
        String recipientPhone,
        BigDecimal newBalance,
        OffsetDateTime createdAt
) {
}
