package com.application.e_wallet.dashboard.admin.dto;

import com.application.e_wallet.dashboard.TransactionTypeMetric;

public record TransactionBreakdown(
        TransactionTypeMetric cashIn,
        TransactionTypeMetric cashOut,
        TransactionTypeMetric serviceFee,
        TransactionTypeMetric commission
) {
}
