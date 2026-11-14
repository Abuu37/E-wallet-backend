package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.Gender;

import java.time.LocalDate;
import java.util.UUID;

public record AgentUpdateResponse(
        UUID agentId,
        String firstName,
        String middleName,
        String lastName,
        Gender gender,
        LocalDate dateOfBirth,
        String regionId,
        String regionName,
        String districtId,
        String districtName,
        String status
) {
}
