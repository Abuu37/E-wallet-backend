package com.application.e_wallet.transaction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionDetailResponse(
        String reference,
        String type,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal commission,
        String status,
        OffsetDateTime createdAt,
        String fromAccountNumber,
        String fromAccountName,
        String toAccountNumber,
        String toAccountName,
        String externalReference
) {
}
