package com.application.e_wallet.dashboard.agent.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

//==== Last Float for the agent request ====== //
public record AgentLatestFloatRequest(
        UUID requestId,
        BigDecimal amount,
        String internalReference,
        String status,
        OffsetDateTime requestedAt
) {
}
