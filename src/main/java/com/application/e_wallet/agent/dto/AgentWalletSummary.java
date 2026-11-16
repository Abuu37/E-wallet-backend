package com.application.e_wallet.agent.dto;

import java.math.BigDecimal;

public record AgentWalletSummary(
        String accountNumber,
        BigDecimal balance,
        String currency
) {
}
