package com.application.e_wallet.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record LowFloatAgentsSummary(
        BigDecimal threshold,
        long count,
        List<LowFloatAgentItem> agents
) {
}
