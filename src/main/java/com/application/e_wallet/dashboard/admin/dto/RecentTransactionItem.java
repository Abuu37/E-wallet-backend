package com.application.e_wallet.dashboard.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RecentTransactionItem(
        String referenceNo,
        String transactionType,
        BigDecimal amount,
        String status,
        OffsetDateTime createdAt,
        String fromAccountName,
        String fromAccountNumber,
        String toAccountName,
        String toAccountNumber
) {
}
