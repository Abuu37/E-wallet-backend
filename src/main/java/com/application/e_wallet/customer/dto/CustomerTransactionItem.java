package com.application.e_wallet.customer.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CustomerTransactionItem(
        String reference,
        String type,
        String direction,
        BigDecimal amount,
        BigDecimal fee,
        String status,
        OffsetDateTime createdAt
) {
}
