package com.application.e_wallet.dashboard.dto;

public record TransactionBreakdown(
        TransactionTypeMetric cashIn,
        TransactionTypeMetric cashOut,
        TransactionTypeMetric internalTransfer,
        TransactionTypeMetric commissionTransfer
) {
}
