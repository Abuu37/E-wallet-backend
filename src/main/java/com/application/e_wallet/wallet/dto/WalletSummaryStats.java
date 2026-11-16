package com.application.e_wallet.wallet.dto;

import java.math.BigDecimal;

public record WalletSummaryStats(
        BigDecimal totalMasterBalance,
        BigDecimal totalAgentFloatBalance,
        BigDecimal totalAgentCommissionBalance,
        BigDecimal totalCustomerBalance
) {
}
