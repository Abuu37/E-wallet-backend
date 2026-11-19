package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.AgentStatus;
import jakarta.validation.constraints.NotNull;

public record AgentStatusUpdateRequest(
        @NotNull
        AgentStatus status
) {
}
