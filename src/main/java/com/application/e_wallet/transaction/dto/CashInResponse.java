package com.application.e_wallet.transaction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CashInResponse(
        String transactionReference,
        String status,
        String currency,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal commission,
        CustomerLeg customer,
        AgentLeg agent,
        OffsetDateTime createdAt
) {
    public record CustomerLeg(
            String phone,
            BigDecimal newBalance
    ) {
    }

    public record AgentLeg(
            String agentAccountNumber,
            BigDecimal newFloatBalance,
            BigDecimal newCommissionBalance
    ) {
    }
}
