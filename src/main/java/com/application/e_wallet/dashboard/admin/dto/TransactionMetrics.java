package com.application.e_wallet.dashboard.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TransactionMetrics(
        String range,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalVolume,
        long totalCount,
        TransactionBreakdown breakdown,
        List<TrendPoint> trend
) {
}
