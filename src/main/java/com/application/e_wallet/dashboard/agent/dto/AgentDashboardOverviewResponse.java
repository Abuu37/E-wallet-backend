package com.application.e_wallet.dashboard.agent.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentDashboardOverviewResponse(
        OffsetDateTime timestamp,
        String currency,
        AgentDashboardProfile agentProfile,
        AgentDashboardWallets wallets,
        AgentFloatRequestStatus floatRequestStatus,
        AgentTodaySummary todaySummary,
        List<AgentRecentTransaction> recentTransactions,
        AgentTransactionVolume transactionVolume
) {
}
