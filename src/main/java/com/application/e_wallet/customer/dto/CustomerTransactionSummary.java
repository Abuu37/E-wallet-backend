package com.application.e_wallet.customer.dto;

import java.time.OffsetDateTime;

public record CustomerTransactionSummary(
        long totalTransactions,
        String lastTransactionReference,
        OffsetDateTime lastTransactionAt
) {
}
