package com.application.e_wallet.dashboard.agent.dto;

public record AgentFloatRequestStatus(
        long pendingCount,
        AgentLatestFloatRequest latestRequest
) {
}
