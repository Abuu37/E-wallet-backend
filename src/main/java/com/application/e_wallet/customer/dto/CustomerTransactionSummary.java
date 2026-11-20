package com.application.e_wallet.customer.dto;

import java.math.BigDecimal;

public record CustomerTransactionSummary(
        BigDecimal availableBalance,
        long totalTransactions,
        BigDecimal totalMoneyIn,
        BigDecimal totalMoneyOut
) {
}
