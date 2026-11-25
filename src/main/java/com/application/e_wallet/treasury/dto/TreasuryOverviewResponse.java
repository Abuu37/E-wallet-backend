package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Escrow is the real money in the trust bank account. Everything else is what that money is owed to. */
public record TreasuryOverviewResponse(
        UUID walletId,
        String currency,
        BigDecimal escrowBalance,
        BigDecimal commissionPoolBalance,
        BigDecimal revenueBalance,
        BigDecimal totalDistributedFloat,
        TreasuryMetrics metrics,
        String createdBy,
        OffsetDateTime createdAt
) {
}
