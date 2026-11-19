package com.application.e_wallet.dashboard.agent.dto;

import java.math.BigDecimal;

public record AgentFloatAccount(
        String accountNumber,
        BigDecimal balance,
        boolean floatHearth
) {
}
