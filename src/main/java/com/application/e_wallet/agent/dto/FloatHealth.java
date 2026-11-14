package com.application.e_wallet.agent.dto;

import java.math.BigDecimal;

public enum FloatHealth {
    HEALTHY,
    LOW,
    CRITICAL;

    private static final BigDecimal LOW_THRESHOLD = new BigDecimal("100000");

    public static FloatHealth fromBalance(BigDecimal currentBalance) {
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return CRITICAL;
        }
        if (currentBalance.compareTo(LOW_THRESHOLD) < 0) {
            return LOW;
        }
        return HEALTHY;
    }
}
