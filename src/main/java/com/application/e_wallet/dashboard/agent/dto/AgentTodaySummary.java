package com.application.e_wallet.dashboard.agent.dto;

import java.math.BigDecimal;

public record AgentTodaySummary(
        BigDecimal commissionEarnedToday,
        long totalTransactionsCount,
        BigDecimal cashInVolume,
        long cashInCount,
        BigDecimal cashOutVolume,
        long cashOutCount
) {
}
