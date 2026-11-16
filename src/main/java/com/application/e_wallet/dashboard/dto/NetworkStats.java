package com.application.e_wallet.dashboard.dto;

public record NetworkStats(
        long totalActiveAgents,
        long totalActiveCustomers,
        TransactionTypeMetric pendingTransactionsToday,
        TransactionTypeMetric failedTransactionsToday
) {
}
