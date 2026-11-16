package com.application.e_wallet.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record LowFloatAgentItem(
        UUID agentId,
        String agentCode,
        String businessName,
        String phoneNumber,
        String floatAccountNumber,
        BigDecimal currentBalance
) {
}
