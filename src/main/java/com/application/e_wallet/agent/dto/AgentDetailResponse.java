package com.application.e_wallet.agent.dto;

import com.application.e_wallet.agent.entity.Gender;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AgentDetailResponse(
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
        BigDecimal floatBalance,
        FloatHealth floatHealth,
        BigDecimal commissionBalance,
        String floatAccountNumber,
        String floatWalletNumber,
        String commissionAccountNumber,
        String commissionWalletNumber,
        String status
) {
}
