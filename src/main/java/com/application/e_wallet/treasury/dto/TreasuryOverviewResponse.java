package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TreasuryOverviewResponse(
        UUID walletId,
        String currency,
        BigDecimal unallocatedFloat,
        BigDecimal totalDistributedFloat,
        String externalReference,
        TreasuryMetrics metrics
) {
}
