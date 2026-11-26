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
        String externalReference,
        /** IN or OUT from the point of view of the person reading; null for staff reading someone else's record. */
        String direction,
        String counterpartyName,
        /** Masked, e.g. 0754 ••• 210. */
        String counterpartyPhone,
        /** Only for withdrawals at an agent. */
        String matchCode
) {
}
