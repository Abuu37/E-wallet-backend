package com.application.e_wallet.agent.dto;

import java.util.List;

public record AgentListResponse(
        List<AgentSummaryResponse> agents,
        int page,
        int size,
        long totalElements
) {
}
