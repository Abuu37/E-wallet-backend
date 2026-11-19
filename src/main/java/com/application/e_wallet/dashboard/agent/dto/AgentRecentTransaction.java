package com.application.e_wallet.dashboard.agent.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record AgentRecentTransaction(
        String internalReference,
        String type,
        String customerName,
        String customerAccount,
        BigDecimal amount,
        BigDecimal commissionEarned,
        String status,
        OffsetDateTime createdAt
) {
}
