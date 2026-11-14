package com.application.e_wallet.company_wallet.dto;

import java.math.BigDecimal;

public record CompanyWalletMetrics(
        BigDecimal pendingRequestAmount,
        long pendingRequestCount,
        BigDecimal approvedRequestAmount,
        long approvedRequestCount
) {
}
