package com.application.e_wallet.dashboard.admin.dto;

public record NetworkStats(
        long totalActiveAgents,
        long totalActiveCustomers,
        TransactionTypeMetric pendingTransactionsToday,
        TransactionTypeMetric failedTransactionsToday
) {
}
