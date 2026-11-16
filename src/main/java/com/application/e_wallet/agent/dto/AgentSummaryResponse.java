package com.application.e_wallet.agent.dto;

import java.util.UUID;

public record AgentSummaryResponse(
        UUID agentId,
        String agentNumber,
        String firstName,
        String middleName,
        String lastName,
        String phone,
        String regionName,
        String districtName,
        String status,
        AgentWalletSummary floatAccount,
        AgentWalletSummary commissionAccount
) {
}
