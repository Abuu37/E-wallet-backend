package com.application.e_wallet.dashboard.agent.dto;

import com.application.e_wallet.dashboard.TransactionTypeMetric;
import com.application.e_wallet.dashboard.TrendPoint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AgentTransactionVolume(
        String range,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalVolume,
        long totalCount,
        TransactionTypeMetric cashIn,
        TransactionTypeMetric cashOut,
        List<TrendPoint> trend
) {
}
