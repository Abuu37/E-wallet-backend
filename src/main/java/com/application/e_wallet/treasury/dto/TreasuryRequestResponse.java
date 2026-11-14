package com.application.e_wallet.treasury.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TreasuryRequestResponse(
        UUID requestId,
        String requestRef,
        String requestType,
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
