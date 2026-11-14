package com.application.e_wallet.agent.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FloatRequestResponse(
        UUID requestId,
        String requestRef,
        UUID agentId,
        String agentNumber,
        String agentName,
        String region,
        String district,
        String phone,
        String walletNumber,
        String accountNumber,
        BigDecimal amount,
        String reason,
        String status,
        String decidedByUserName,
        String decisionNote,
        OffsetDateTime decidedAt,
        OffsetDateTime createdAt
) {
}
