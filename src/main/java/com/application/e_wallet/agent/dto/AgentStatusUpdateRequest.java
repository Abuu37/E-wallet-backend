package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.AgentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentStatusUpdateRequest(
        @NotNull
        AgentStatus status,

        @NotBlank(message = "A reason is required to change an agent's status")
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        String reason
) {
}
