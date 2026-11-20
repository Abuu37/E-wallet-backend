package com.application.e_wallet.agent.dto;

import java.math.BigDecimal;

public record AgentListSummary(
        long totalAgents,
        long activeAgents,
        long suspendedAgents,
        BigDecimal totalAgentFloatBalance
) {
}
