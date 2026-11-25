package com.application.e_wallet.dashboard.admin.dto;

import java.math.BigDecimal;

public record LiquidityCards(
        BigDecimal escrowBankBalance,
        BigDecimal commissionPoolBalance,
        BigDecimal companyRevenueBalance,
        BigDecimal totalAgentFloatBalance,
        BigDecimal totalAgentCommissionBalance,
        BigDecimal totalCustomerWalletBalance,
        boolean isLedgerBalanced
) {
}
