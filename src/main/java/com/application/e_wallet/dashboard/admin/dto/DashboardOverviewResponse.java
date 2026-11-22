package com.application.e_wallet.dashboard.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record DashboardOverviewResponse(
        OffsetDateTime timestamp,
        String currency,
        LiquidityCards liquidityCards,
        TransactionMetrics transactionMetrics,
        ActionAlerts actionAlerts,
        NetworkStats networkStats,
        List<RecentTransactionItem> recentTransactions
) {
}
