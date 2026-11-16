package com.application.e_wallet.dashboard.dto;

public record ActionAlerts(
        PendingFloatRequestsSummary pendingFloatRequests,
        LowFloatAgentsSummary lowFloatAgents
) {
}
