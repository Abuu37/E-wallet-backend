package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.Gender;

import java.time.LocalDate;
import java.util.UUID;

public record AgentOnboardResponse(
        UUID agentId,
        String agentNumber,
        UUID userId,
        String firstName,
        String middleName,
        String lastName,
        String email,
        String phone,
        Gender gender,
        LocalDate dateOfBirth,
        String regionId,
        String regionName,
        String districtId,
        String districtName,
        String floatAccountNumber,
        String commissionAccountNumber,
        String status
) {
}
