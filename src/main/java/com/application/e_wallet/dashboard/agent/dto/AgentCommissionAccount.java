package com.application.e_wallet.dashboard.agent.dto;

import java.math.BigDecimal;

public record AgentCommissionAccount(
        String accountNumber,
        BigDecimal balance
) {
}
