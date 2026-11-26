package com.application.e_wallet.transaction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of "my transactions", seen from the caller's side: direction IN or OUT, and who was on the other side. */
public record MyTransactionItem(
        String referenceNo,
        String type,
        String direction,
        BigDecimal amount,
        BigDecimal fee,
        String status,
        OffsetDateTime createdAt,
        String counterpartyName,
        String counterpartyPhone,
        String matchCode
) {
}
