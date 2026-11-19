package com.application.e_wallet.dashboard.admin.dto;

import java.math.BigDecimal;
import java.util.List;

public record TodayTransactionMetrics(
        BigDecimal totalVolume,
        long totalCount,
        TransactionBreakdown breakdown,
        List<HourlyTrendPoint> hourlyTrend
) {
}
