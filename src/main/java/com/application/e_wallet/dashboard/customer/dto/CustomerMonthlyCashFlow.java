package com.application.e_wallet.dashboard.customer.dto;

import java.math.BigDecimal;

public record CustomerMonthlyCashFlow(
        String monthLabel,
        BigDecimal moneyIn,
        BigDecimal moneyOut,
        long transactionCount
) {
}
