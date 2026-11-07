package com.application.e_wallet.agent.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AgentCreationResponse(
        UUID agentId,
        String agentNumber,
        UUID userId,
        String phone,
        String walletNumber,
        String accountNumber,
        BigDecimal currentBalance
) {
}
