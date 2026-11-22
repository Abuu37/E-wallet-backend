package com.application.e_wallet.dashboard.agent.dto;

import com.application.e_wallet.agent.dto.FloatHealth;

import java.math.BigDecimal;

public record AgentSummary(
        BigDecimal totalFloatBalance,
        BigDecimal totalCommissionBalance,
        long totalTransactions,
        FloatHealth floatHealth
) {
}
