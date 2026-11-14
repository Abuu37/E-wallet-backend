package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;

public record TreasuryMetrics(
        BigDecimal pendingRequestAmount,
        long pendingRequestCount,
        BigDecimal approvedRequestAmount,
        long approvedRequestCount
) {
}
