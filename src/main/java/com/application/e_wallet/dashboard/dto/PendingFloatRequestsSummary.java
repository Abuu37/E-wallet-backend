package com.application.e_wallet.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record PendingFloatRequestsSummary(
        long count,
        BigDecimal totalRequestedAmount,
        List<PendingFloatRequestItem> recentRequests
) {
}
