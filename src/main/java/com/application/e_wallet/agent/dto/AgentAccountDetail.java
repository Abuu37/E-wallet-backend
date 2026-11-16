package com.application.e_wallet.agent.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record AgentAccountDetail(
        String accountNumber,
        BigDecimal balance,
        String status,
        OffsetDateTime lastTransactionAt
) {
}
