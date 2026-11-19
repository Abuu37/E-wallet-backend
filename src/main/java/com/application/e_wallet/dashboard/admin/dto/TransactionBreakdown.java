package com.application.e_wallet.dashboard.admin.dto;

public record TransactionBreakdown(
        TransactionTypeMetric cashIn,
        TransactionTypeMetric cashOut,
        TransactionTypeMetric serviceFee,
        TransactionTypeMetric commission
) {
}
