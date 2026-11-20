package com.application.e_wallet.agent.dto;

import java.util.List;

public record AgentListResponse(
        AgentListSummary summary,
        List<AgentSummaryResponse> agents,
        int page,
        int size,
        long totalElements
) {
}
