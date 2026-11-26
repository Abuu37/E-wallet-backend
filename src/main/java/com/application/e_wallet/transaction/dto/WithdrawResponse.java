package com.application.e_wallet.transaction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** matchCode is what the customer shows the agent: the agent sees the same code on their screen. */
public record WithdrawResponse(
        String referenceNo,
        String status,
        String currency,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal total,
        String matchCode,
        AgentInfo agent,
        BigDecimal newBalance,
        OffsetDateTime createdAt
) {
    public record AgentInfo(String businessName, String tillNumber, String district, String region) {
    }
}
