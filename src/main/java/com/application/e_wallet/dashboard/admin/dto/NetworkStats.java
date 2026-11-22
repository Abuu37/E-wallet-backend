package com.application.e_wallet.dashboard.admin.dto;

import com.application.e_wallet.dashboard.TransactionTypeMetric;

public record NetworkStats(
        long totalActiveAgents,
        long totalActiveCustomers,
        TransactionTypeMetric pendingTransactionsToday,
        TransactionTypeMetric failedTransactionsToday
) {
}
